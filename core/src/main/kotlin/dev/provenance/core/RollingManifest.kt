package dev.provenance.core

/**
 * The ROLLING SEAL — per-session manifests the recorder maintains as the student works.
 * Kotlin port of log-core's `rolling-manifest.ts`, which is the authoritative design.
 * This file is the *format* half (names, version, manifest construction); the I/O half is
 * `recorder/io/RollingSeal.kt`.
 *
 * ## Why it exists
 *
 * A classic bundle is sealed once, by an explicit "Prepare Submission Bundle" command,
 * into `.provenance/manifest.json` + `manifest.sig`. That works because there is a
 * packaging step to hook: the student runs seal, gets a `.zip`, uploads it.
 *
 * A git-submitted assignment has no such step. The student pushes; Gradescope clones.
 * Nothing ever runs seal, so a git-submitted `.provenance/` carries no signed manifest at
 * all and every scope reports `no_seal`. The fix is to move the seal off the submission
 * event and onto the recording itself: the recorder rewrites a seal of its own session on
 * every checkpoint, so **whatever is committed is always a valid seal of the state at that
 * moment**.
 *
 * ## Why per-session filenames, and why that is not cosmetic
 *
 * The rolling seal lives at `.provenance/manifest-<session_id>.json` (+ `.sig`).
 *
 * In a shared repo two partners' recorders both write into the SAME `.provenance/`
 * directory, and both partners push. A single shared `manifest.json` would then conflict on
 * every merge — two different signed blobs at one path, which git cannot reconcile and a
 * student cannot hand-resolve without destroying a signature. Per-session filenames make
 * the directory **add-only**: each recorder only ever writes files named after its own
 * session, so a merge is a union of disjoint paths. Identical property to the one that
 * already makes `session-<uuid>.slog` mergeable, applied to the seal.
 *
 * It follows that each rolling manifest covers **only its own session** and is signed by
 * **that session's** ephemeral key. Nothing needs a key it does not have.
 *
 * ## Format version 1.2
 *
 * A rolling manifest is a [BundleManifest] at [ROLLING_MANIFEST_FORMAT_VERSION]. It reuses
 * the 1.1 field set verbatim and adds exactly one rule, which is why it needs a version of
 * its own rather than being a 1.1 with one session:
 *
 *   **A rolling manifest FILE describes exactly one session, whose `session_id` is
 *   non-null and equals the id in its filename.**
 *
 * The filename binding is what stops a rolling manifest being copied sideways:
 * `manifest-A.json` claiming session B would be verified against B's pubkey but read as A's
 * seal. [buildRollingSessionManifest] is the one place the write side can honour that rule,
 * and it takes the session id ONCE so the manifest body and the filename cannot disagree.
 *
 * ## The `final` marker — closing the append hole
 *
 * A rolling seal is signed BEFORE the log's trailing bytes exist. It is rewritten at session
 * start, at every checkpoint, and at teardown, hashing the `.slog` as it stands at that
 * moment, so its `slog_sha256` legitimately commits only to a PREFIX. That is not a defect —
 * it is what stops an honest mid-session archive being read as tampering — but it costs
 * something real: an entry appended past the last checkpoint is indistinguishable from
 * honest mid-session growth.
 *
 * `final: true` recovers that. The recorder writes it on exactly ONE roll: the teardown one,
 * taken after `session.end` has been emitted and the writer flushed, when the log provably
 * will not grow again.
 *
 *   final seal      => WHOLE-FILE. Any append, truncation or edit fails.
 *   non-final seal  => PREFIX, exactly as before. Honest growth is never a finding.
 *
 * Three properties make this safe rather than a new way to accuse people:
 *
 *  1. **It is inside the signed payload.** [signBundleManifest] canonicalizes the whole
 *     manifest, so a student cannot add, flip, or strip `final` without the session's
 *     private key.
 *  2. **Absence is never a finding.** A session that dies without a clean teardown — a
 *     crash, a power cut, a full disk, a read-only `.provenance/`, the directory removed by
 *     a `git checkout` — simply has no final seal and falls back to prefix semantics with
 *     the unattested tail REPORTED. Every one of those paths belongs to a student who did
 *     nothing wrong. That is exactly why finality is an explicit, signed claim by the
 *     WRITER rather than something a reader infers from a trailing `session.end` entry:
 *     `session.end` is in the log, and the log is the thing whose completeness is in
 *     question.
 *  3. **It is a one-way ratchet.** `final` only ever tightens what a reader will accept.
 *
 * ## What a rolling manifest does NOT change
 *
 * Nothing about `manifest.json` / `manifest.sig`. A classic sealed bundle is byte-for-byte
 * what it was: same 1.1 manifest, same canonical bytes, same signature, same loader path.
 * The rolling seal is a second, additive shape.
 *
 * Pure: names + construction only, no I/O.
 */

/** `format_version` of a rolling seal. */
const val ROLLING_MANIFEST_FORMAT_VERSION = "1.2"

/**
 * `manifest-<session_id>.json` / `manifest-<session_id>.sig`.
 *
 * The session-id character class matches the one `session-<uuid>.slog` files use (hex +
 * dashes), so a rolling manifest can only ever be named after something that could be a
 * session id. `manifest.json` and `manifest.sig` cannot match: the `-` after `manifest` is
 * mandatory and the id is non-empty.
 */
private val ROLLING_MANIFEST_RE = Regex("^manifest-([0-9a-f-]+)\\.(json|sig)$")

/** The two filenames a session's rolling seal occupies inside `.provenance/`. */
data class RollingManifestFilenames(val json: String, val sig: String)

/** Which half of a session's rolling seal a filename names. */
enum class RollingManifestPart { JSON, SIG }

/** A `.provenance/` entry name read as a rolling-seal file. */
data class RollingManifestFile(val sessionId: String, val part: RollingManifestPart)

/**
 * The single definition of a rolling seal's filenames. The writer never spells these by
 * hand, so it cannot drift from [parseRollingManifestFilename].
 */
fun rollingManifestFilenames(sessionId: String): RollingManifestFilenames {
    require(sessionId.isNotEmpty()) { "sessionId must be non-empty" }
    return RollingManifestFilenames("manifest-$sessionId.json", "manifest-$sessionId.sig")
}

/**
 * Read a `.provenance/` entry name as a rolling-seal file.
 *
 * Returns null for anything that is not one — including `manifest.json` and `manifest.sig`,
 * which belong to the CLASSIC seal and must never be produced or consumed by this path.
 */
fun parseRollingManifestFilename(filename: String): RollingManifestFile? {
    val m = ROLLING_MANIFEST_RE.matchEntire(filename) ?: return null
    val part = if (m.groupValues[2] == "json") RollingManifestPart.JSON else RollingManifestPart.SIG
    return RollingManifestFile(m.groupValues[1], part)
}

/**
 * Build the 1.2 manifest for ONE session's rolling seal.
 *
 * The exactly-one-session rule is enforced by construction: this function takes a single
 * session's four fields, not a list, so there is no shape of a caller that can put two
 * sessions (or zero, or a null id) into a rolling manifest file.
 *
 * @param isFinal Mark this seal FINAL — the last one this session will ever get, so its
 *   digests commit to the WHOLE log rather than to a prefix. Set by exactly ONE caller: the
 *   teardown roll, after `session.end` has been emitted, the writer flushed and the pending
 *   checkpoint drained. That is the only moment at which the claim is true. Never set it on
 *   the session-start roll or on a checkpoint roll: that would assert that a log which is
 *   about to keep growing is finished, and a reader would then read the student's own next
 *   keystroke as an append past a final seal — a manufactured finding against someone who
 *   is still working.
 */
fun buildRollingSessionManifest(
    sessionId: String,
    prevSessionId: String?,
    slogSha256: String,
    metaSha256: String,
    assignmentId: String,
    semester: String,
    extensionHash: String,
    submissionFiles: List<SubmissionFileEntry>,
    isFinal: Boolean = false,
): BundleManifest {
    require(sessionId.isNotEmpty()) { "a rolling manifest's session_id must be non-empty" }
    return BundleManifest(
        formatVersion = ROLLING_MANIFEST_FORMAT_VERSION,
        assignmentId = assignmentId,
        semester = semester,
        extensionHash = extensionHash,
        sessions = listOf(SessionEntry(sessionId, prevSessionId, slogSha256, metaSha256)),
        submissionFiles = submissionFiles,
        isFinal = isFinal,
    )
}
