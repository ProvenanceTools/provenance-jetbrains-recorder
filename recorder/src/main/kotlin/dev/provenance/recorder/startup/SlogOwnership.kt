package dev.provenance.recorder.startup

import dev.provenance.core.parseIsoInstantMs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whose `.slog` is this? The Kotlin twin of the VS Code recorder's ownership table
 * (`provenance/packages/recorder/src/startup/chain-recovery.ts`, "Decision — OWNERSHIP");
 * `slog_ownership.lua` is the Lua twin. All three implement the same table and the same
 * wall-order selection — see the collaboration spec §3 S9/S19/S22 and decision-log bug 2
 * for the rationale, which is NOT restated here.
 *
 * Why it exists, in one paragraph: `.provenance/` is committed, so in a shared repo (two
 * partners, one git repo — the standard CS 61B/61C layout) a `git pull` drops the PARTNER'S
 * `.slog` into the directory this recorder writes into. Recovery used to take the
 * alphabetically last file and rename it to `<slog>.corrupt-<ts>` whenever it failed to
 * read, parse, or chain-validate. Sealing skips `.corrupt-` files, so that rename deletes a
 * partner's evidence from the submission — and it is a free attack: flip one byte of their
 * log and their own tooling erases it for you.
 *
 * The signal is `session.start.identity.enrollment.student_ref` and ONLY that. Not
 * `machine_id` (salted with the session id, so it never matches across sessions), not
 * `session_pubkey` (fresh per session by design), not the filename UUID (minted per file
 * and unrelated to any identity) — and NOT the `wall` clock, which is read from the same
 * line but is a strictly separate fact. A damaged `wall` costs a `.slog` its ORDER; it
 * must never cost it its AUTHOR. See [parseSessionStartHead].
 *
 * ```
 *   own           both refs present and equal        eligible: may be selected, linked,
 *                                                    and quarantined
 *   foreign       refs differ, OR we have none       NEVER touched. Not selected, not
 *                 and the candidate has one          linked, not renamed
 *   unattributed  the candidate names nobody         eligible ONLY when this recorder is
 *                                                    itself unattributed
 * ```
 *
 * RESIDUAL GAP, stated rather than papered over: when NEITHER partner has enrolled every
 * file is `unattributed` and no signal can separate them. Closing that needs enrollment or
 * peer witnessing; nothing here can.
 *
 * NO WRITE-CAPABLE SEAM. [selectEligible] is handed a read function and nothing else, so
 * the scan that decides ownership physically cannot act on the file it is classifying.
 * Quarantine lives in `ChainRecovery.kt`, at one call site, on the selected path only.
 */
enum class SlogOwnership { Own, Foreign, Unattributed }

/**
 * The eligible `.slog` recovery should operate on.
 *
 * [text] is the already-read bytes when this is the wall-order winner, so the caller need
 * not re-read it; it is null on the alphabetically-last-eligible FALLBACK, where by
 * definition nothing parseable was found and the caller re-reads to produce the read error
 * the corrupt path reports.
 */
data class EligiblePick(val filename: String, val text: String?)

/**
 * Classify a candidate `.slog` from the two `student_ref`s.
 *
 * Note the asymmetric `foreign` case: when this recorder has NO identity and the candidate
 * HAS one, the candidate is foreign rather than unattributed. We cannot claim to be a
 * contributor we cannot name, and the cost of being wrong is asymmetric — misclassifying
 * our own pre-enrollment log as foreign loses a back-pointer, while misclassifying a
 * partner's log as ours destroys it.
 */
fun classifySlogOwnership(ownStudentRef: String?, candidateStudentRef: String?): SlogOwnership {
    // A candidate that names nobody can never be PROVEN ours, whoever we are.
    if (candidateStudentRef == null) return SlogOwnership.Unattributed
    if (ownStudentRef == null) return SlogOwnership.Foreign
    return if (candidateStudentRef == ownStudentRef) SlogOwnership.Own else SlogOwnership.Foreign
}

/**
 * May this recorder select, link to, and (if corrupt) quarantine a candidate of the given
 * class?
 *
 * `own` always. `foreign` never. `unattributed` only when this recorder is itself
 * unattributed, because only then is the directory indistinguishable from a solo one and
 * today's behaviour the honest default — refusing to act there would silently switch off
 * crash recovery for every student who has not enrolled.
 */
fun isEligible(ownership: SlogOwnership, ownStudentRef: String?): Boolean = when (ownership) {
    SlogOwnership.Own -> true
    SlogOwnership.Foreign -> false
    SlogOwnership.Unattributed -> ownStudentRef == null
}

/**
 * The two facts the first line yields, read INDEPENDENTLY of one another: when the
 * session started, and whose it claims to be.
 *
 * [wallMs] is nullable on purpose. A file with no usable timestamp is *unorderable*; it
 * is emphatically not *unattributable*, and conflating the two is what let a flipped byte
 * in a timestamp erase a partner's evidence.
 */
internal data class SlogStartHead(val wallMs: Long?, val studentRef: String?)

private val SLOG_HEAD_JSON = Json { ignoreUnknownKeys = true }

/**
 * Parse the FIRST LINE of a `.slog` for its `session.start` wall and `student_ref`.
 *
 * Only the first line: this runs once per file at startup and must not become proportional
 * to log size. `session.start` is always seq 0, so one line is enough.
 *
 * Returns null ONLY when the first line is not a parseable `session.start` at all — then
 * the file genuinely cannot say whose it is, and its ownership is `unattributed`.
 *
 * A `session.start` that IS parseable always yields its `student_ref`, even when its
 * `wall` does not parse. The two used to be all-or-nothing, and that was a live
 * evidence-destruction defect: `session.start.wall` is a plain string in the clear, so
 * flipping one byte of a classmate's timestamp made the whole parse fail, threw away
 * their `student_ref` with it, and demoted their log to `unattributed`. An UNENROLLED
 * recorder may select and quarantine `unattributed` files, so an innocent bystander's
 * tooling renamed the victim's log `.corrupt-<ISO>` — which sealing excludes — with the
 * bystander's commit as the paper trail. Ownership is `student_ref` and only
 * `student_ref`, which is what the class table always claimed.
 *
 * `wall` goes through core's [parseIsoInstantMs] rather than `Instant.parse` so the three
 * ports share one accepting set (see its KDoc).
 */
internal fun parseSessionStartHead(text: String): SlogStartHead? {
    val firstLine = text.substringBefore('\n')
    if (firstLine.isBlank()) return null

    val entry = try {
        SLOG_HEAD_JSON.parseToJsonElement(firstLine) as? JsonObject ?: return null
    } catch (_: Exception) {
        // kotlinx.serialization throws SerializationException on malformed JSON; a
        // half-written or conflict-marked line is ORDINARY here, never exceptional.
        return null
    }

    if (stringOf(entry["kind"]) != "session.start") return null

    // A missing, non-string, or unparseable `wall` yields null: an UNORDERABLE candidate,
    // never an UNATTRIBUTABLE one. Note this does NOT short-circuit the student_ref read.
    val wallMs = stringOf(entry["wall"])?.let { parseIsoInstantMs(it) }

    return SlogStartHead(wallMs, extractStudentRef(entry["data"]))
}

private fun stringOf(element: kotlinx.serialization.json.JsonElement?): String? {
    val p = element as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/**
 * Pull `data.identity.enrollment.student_ref` out of an untyped `session.start` payload,
 * or null when any hop is missing or the wrong shape.
 *
 * Narrowed by hand rather than by a schema: 1.x payloads have no `identity` at all, this
 * runs on a file written by a possibly-different recorder version — possibly a different
 * EDITOR's recorder — and "absent" must never become "throws".
 */
private fun extractStudentRef(data: kotlinx.serialization.json.JsonElement?): String? {
    val identity = (data as? JsonObject)?.get("identity") as? JsonObject ?: return null
    val enrollment = identity["enrollment"] as? JsonObject ?: return null
    return stringOf(enrollment["student_ref"])?.ifEmpty { null }
}

/**
 * Scan [slogFiles] once and return the ELIGIBLE `.slog` recovery may operate on, or null
 * when nothing in the directory is eligible (then the caller starts clean and leaves every
 * file exactly where it is).
 *
 * Selection among eligible files is the latest parseable `session.start.wall`, with ties
 * broken by filename descending so the choice stays deterministic. The FALLBACK, when no
 * eligible file yields a parseable start, is the alphabetically last ELIGIBLE filename, so
 * the corrupt/quarantine path still runs on something we are entitled to touch.
 *
 * [slogFiles] must already be sorted ascending — the fallback relies on it.
 *
 * Wall clock orders sessions ALREADY known to be the same contributor's, which is the
 * narrowest defensible use of it: it is a display-grade primitive even between one
 * person's two machines. The authoritative fix is a signed per-contributor session ordinal,
 * which is a `session.start` format change and out of scope here.
 *
 * Reads are sequential, never concurrent: only one file's text is held at a time besides
 * the current best.
 */
suspend fun selectEligible(
    slogFiles: List<String>,
    provenanceDir: String,
    readSlogFile: suspend (String) -> SlogReadResult,
    ownStudentRef: String?,
): EligiblePick? {
    var bestFilename: String? = null
    var bestText: String? = null
    var bestWallMs: Long = Long.MIN_VALUE
    var eligibleFallback: String? = null

    for (filename in slogFiles) {
        val read = readSlogFile("$provenanceDir/$filename")

        // An unreadable file tells us nothing about whose it is.
        val head = (read as? SlogReadResult.Ok)?.let { parseSessionStartHead(it.text) }
        val ownership = classifySlogOwnership(ownStudentRef, head?.studentRef)

        // A foreign session is dropped HERE, before anything else can happen to it: never
        // selected, never linked, never renamed. Everything downstream operates on this
        // recorder's own sessions only.
        if (!isEligible(ownership, ownStudentRef)) continue

        // slogFiles arrives sorted, so the last eligible one seen is the alphabetically
        // last eligible one.
        eligibleFallback = filename

        // Eligible but unorderable: unreadable, no `session.start`, or no parseable
        // `wall`. It stays available as `eligibleFallback` above — it IS ours to touch —
        // but it cannot compete for `best`, which is ordered on wall. This skip MUST stay
        // below the fallback assignment; above it, an unorderable own log would silently
        // lose its own crash recovery.
        val wallMs = head?.wallMs ?: continue

        if (bestFilename == null ||
            wallMs > bestWallMs ||
            (wallMs == bestWallMs && filename > bestFilename)
        ) {
            bestFilename = filename
            bestText = (read as SlogReadResult.Ok).text
            bestWallMs = wallMs
        }
    }

    return when {
        bestFilename != null -> EligiblePick(bestFilename, bestText)
        eligibleFallback != null -> EligiblePick(eligibleFallback, null)
        else -> null
    }
}
