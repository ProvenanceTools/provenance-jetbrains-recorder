package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Per-event-kind payload shapes (recorder PRD §4.2, §5.1). Ported from log-core's
 * events.ts. Lives in core/ (not recorder/) because the payload shape is part of
 * the format contract, not editor-specific — mirrors where log-core places it.
 *
 * Plan 4 emits session.start/heartbeat/end, doc.open/change/save/close. Plan 5
 * adds fs.external_change (below). Later plans add paste, selection.change,
 * focus.change, terminal.*, git.event, ext.*, recorder.* here.
 */

data class Position(val line: Long, val character: Long)

data class Range(val start: Position, val end: Position)

private fun Position.toJsonObject(): JsonObject = buildJsonObject {
    put("line", line)
    put("character", character)
}

private fun Range.toJsonObject(): JsonObject = buildJsonObject {
    put("start", start.toJsonObject())
    put("end", end.toJsonObject())
}

/**
 * Editor/host metadata. Replaces the VS Code-shaped `vscode` block in
 * `session.start` 2.0 (program spec §5).
 *
 * provjet and provnvim previously had to pretend into a field named `vscode`,
 * filling it with editor-generic values because renaming a signed field is a
 * monorepo-owned format change. `host` un-warps that: this recorder can finally
 * say `jetbrains` in a field that means what it says.
 *
 * `vscode` is still emitted alongside it through the reader-before-writer
 * migration (program spec §9) so 1.x readers keep working; a later change drops it.
 */
data class HostInfo(
    /** `"vscode"`, `"jetbrains"`, or `"neovim"`. */
    val editor: String,
    val editorVersion: String,
    /** Editor build/commit identifier. `""` is permitted. */
    val editorBuild: String,
    val platform: String,
)

fun HostInfo.toJsonObject(): JsonObject = buildJsonObject {
    put("editor", editor)
    put("editor_version", editorVersion)
    put("editor_build", editorBuild)
    put("platform", platform)
}

data class SessionStartPayload(
    val formatVersion: String,
    val sessionId: String,
    val prevSessionId: String?,
    val assignmentId: String,
    val assignmentSemester: String,
    val manifestSig: String,
    val machineId: String,
    val vscodeVersion: String,
    val vscodeCommit: String,
    val vscodePlatform: String,
    val recorderVersion: String,
    val recorderExtensionId: String,
    val sessionPubkey: String,
    /**
     * The FULL manifest: signed payload + `sig` + `course_cert` (program spec §5).
     * Emitted for 1.x manifests too — it is additive, and a 1.x manifest's parsed
     * form carries no 2.0-only fields, so nothing unsigned can ride along.
     *
     * Nullable at the type level so every pre-2.0 construction site stays valid.
     */
    val manifest: Manifest? = null,
    /** Editor/host metadata (program spec §5). Nullable for the same reason. */
    val host: HostInfo? = null,
    /**
     * The student's enrollment identity for this session (program spec §5, §S2).
     *
     * Null — and the key then ABSENT from the payload, never present-and-empty —
     * whenever an identity cannot be produced or cannot be verified. Not being
     * enrolled is the ordinary pre-enrollment state, not an error, and it must
     * never stop a session recording.
     */
    val identity: SessionIdentity? = null,
    /**
     * The three `session.start` CAPABILITY REPORTS (collaboration spec §5.6). See
     * `SessionCapabilities.kt` for the full contract. Each is nullable and OMITTED — never
     * emitted as JSON `null` — when there is nothing to report; [toJsonObject] enforces that
     * structurally via `?.let { put(...) }`.
     */
    val gitCapture: GitCaptureCapability? = null,
    /** @see gitCapture */
    val witnessCapture: WitnessCaptureCapability? = null,
    /** @see gitCapture */
    val fileScope: SessionFileScope? = null,
)

fun SessionStartPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("session_id", sessionId)
    put("prev_session_id", prevSessionId)
    put(
        "assignment",
        buildJsonObject {
            put("id", assignmentId)
            put("semester", assignmentSemester)
        },
    )
    put("manifest_sig", manifestSig)
    put("machine_id", machineId)
    put(
        "vscode",
        buildJsonObject {
            put("version", vscodeVersion)
            // Present-and-empty, never absent: validators accept '' but require the field (PRD §5.4).
            put("commit", vscodeCommit)
            put("platform", vscodePlatform)
        },
    )
    put(
        "recorder",
        buildJsonObject {
            put("version", recorderVersion)
            put("extension_id", recorderExtensionId)
        },
    )
    put("session_pubkey", sessionPubkey)
    // Omitted when null, never emitted as JSON null — mirrors JSON.stringify dropping
    // `undefined` in log-core, so a pre-2.0 payload serializes byte-identically.
    // Key order here is irrelevant to integrity: chainEntry canonicalizes (JCS) before
    // hashing, and JCS sorts keys.
    manifest?.let { put("manifest", it.toJsonObject()) }
    host?.let { put("host", it.toJsonObject()) }
    // Absent, never present-and-empty, when the student is not enrolled or the block
    // failed its chain walk. An unverifiable identity claim inside a signed, hash-chained
    // entry is permanent and unrepairable, so emitting nothing is strictly better than
    // emitting something broken.
    identity?.let { put("identity", it.toJsonObject()) }
    // The three §5.6 capability reports. Omitted, never present-and-null, when there is
    // nothing to report — see SessionCapabilities.kt.
    gitCapture?.let { put("git_capture", it.wire) }
    witnessCapture?.let { put("witness_capture", it.wire) }
    fileScope?.let { put("file_scope", it.toJsonObject()) }
}

data class SessionHeartbeatPayload(val focused: Boolean, val activeFile: String?, val idleSinceMs: Long)

fun SessionHeartbeatPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("focused", focused)
    put("active_file", activeFile)
    put("idle_since_ms", idleSinceMs)
}

data class SessionEndPayload(val reason: String)

fun SessionEndPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("reason", reason)
}

data class DocOpenPayload(
    val path: String,
    val sha256: String,
    val lineCount: Long,
    val content: String?,
    val truncated: Boolean?,
)

fun DocOpenPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("sha256", sha256)
    put("line_count", lineCount)
    // Optional fields (PRD §4.2): omitted when null, not emitted as JSON null.
    if (content != null) put("content", content)
    if (truncated != null) put("truncated", truncated)
}

data class DocChangeDelta(val range: Range, val text: String)

data class DocChangePayload(val path: String, val deltas: List<DocChangeDelta>, val source: String)

fun DocChangePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put(
        "deltas",
        buildJsonArray {
            for (d in deltas) {
                add(
                    buildJsonObject {
                        put("range", d.range.toJsonObject())
                        put("text", d.text)
                    },
                )
            }
        },
    )
    put("source", source)
}

/**
 * paste event payload (recorder PRD §4.3). Mirrors log-core's PastePayload
 * (events.ts:95-103). A `paste`-kind event is inherently the high-confidence
 * shape — there is deliberately NO `source` field (unlike DocChangePayload).
 * content is inlined for small pastes; content_head/content_tail carry a 512-char
 * truncation for large ones (the builder in recorder/paste enforces which).
 */
data class PastePayload(
    val path: String,
    val range: Range,
    val length: Long,
    val sha256: String,
    val content: String? = null,
    val contentHead: String? = null,
    val contentTail: String? = null,
)

fun PastePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("range", range.toJsonObject())
    put("length", length)
    put("sha256", sha256)
    // Optional fields (events.ts:95-103): omitted when null, never emitted as JSON null.
    if (content != null) put("content", content)
    if (contentHead != null) put("content_head", contentHead)
    if (contentTail != null) put("content_tail", contentTail)
}

/**
 * paste.anomaly payload (recorder PRD §4.3). Mirrors log-core's PasteAnomalyPayload
 * (events.ts:199-202). Deltas since the last periodic check, not cumulative totals.
 */
data class PasteAnomalyPayload(val interceptedCount: Int, val largeInsertCount: Int)

fun PasteAnomalyPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("intercepted_count", interceptedCount)
    put("large_insert_count", largeInsertCount)
}

data class DocSavePayload(val path: String, val sha256: String)

fun DocSavePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("sha256", sha256)
}

data class DocClosePayload(val path: String)

fun DocClosePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
}

/**
 * selection.change payload (recorder PRD §4.2). Mirrors log-core's SelectionChangePayload
 * (events.ts:105-109) and the VS Code doc-events.ts transformSelectionChange. [range] is the
 * primary caret/selection extent; [wasSelection] is false for a bare cursor move (start == end),
 * true when text is actually selected.
 */
data class SelectionChangePayload(val path: String, val range: Range, val wasSelection: Boolean)

fun SelectionChangePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("range", range.toJsonObject())
    put("was_selection", wasSelection)
}

/**
 * focus.change payload (recorder PRD §4.2). Mirrors log-core's FocusChangePayload
 * (events.ts:111-114) and the VS Code doc-events.ts transformFocusChange, which emits only
 * `gained`. [reason] is an always-optional field in the format contract (omitted when null,
 * never emitted as JSON null); the VS Code recorder never populates it, and neither does this
 * host — kept for shape parity so a future analyzer field never requires a format bump.
 */
data class FocusChangePayload(val gained: Boolean, val reason: String? = null)

fun FocusChangePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("gained", gained)
    if (reason != null) put("reason", reason)
}

/**
 * fs.external_change payload (recorder PRD §4.5). Mirrors log-core's
 * FsExternalChangePayload (events.ts:137). Field names are already snake_case on
 * the wire — no camel→snake remap beyond the Kotlin property names.
 *
 * [oldHash]/[newHash] direction is fixed: old = the expected-content model (what the
 * editor believes the file held), new = on-disk reality at detection time. See
 * dev.provenance.recorder.events.classifySavedContent, which enforces it.
 *
 * [explanation] ("formatter"/"git") is threaded through as an always-null optional in
 * Plan 5; Plan 7's terminal/git wiring populates it.
 */
data class FsExternalChangePayload(
    val path: String,
    val oldHash: String,
    val newHash: String,
    val diffSize: Int,
    val explanation: String? = null,
    val operation: String? = null,
    val newContentSize: Int? = null,
    val newContent: String? = null,
    val newContentHead: String? = null,
    val newContentTail: String? = null,
)

fun FsExternalChangePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("old_hash", oldHash)
    put("new_hash", newHash)
    put("diff_size", diffSize)
    // Optional fields (events.ts:137): omitted when null, never emitted as JSON null.
    if (explanation != null) put("explanation", explanation)
    if (operation != null) put("operation", operation)
    if (newContentSize != null) put("new_content_size", newContentSize)
    if (newContent != null) put("new_content", newContent)
    if (newContentHead != null) put("new_content_head", newContentHead)
    if (newContentTail != null) put("new_content_tail", newContentTail)
}

/**
 * terminal.open payload (recorder PRD §4.4). Mirrors log-core's TerminalOpenPayload
 * (events.ts:116-120). Emitted once per terminal when its shell-integration status is
 * known. [shellIntegration] is best-effort: many shells never resolve integration, in
 * which case the recorder records the gap (false) rather than failing (PRD §4.4).
 */
data class TerminalOpenPayload(val terminalId: String, val shell: String, val shellIntegration: Boolean)

fun TerminalOpenPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("terminal_id", terminalId)
    put("shell", shell)
    put("shell_integration", shellIntegration)
}

/**
 * terminal.command payload (recorder PRD §4.4). Mirrors log-core's TerminalCommandPayload
 * (events.ts:122-126). [exitCode] is optional — omitted when null, never emitted as JSON
 * null — because shell integration may report a command with no exit code available.
 */
data class TerminalCommandPayload(val terminalId: String, val command: String, val exitCode: Int?)

fun TerminalCommandPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("terminal_id", terminalId)
    put("command", command)
    if (exitCode != null) put("exit_code", exitCode)
}

/**
 * git.event payload (recorder PRD §4.4), carrying enough of the commit graph for replay to
 * show branch and merge structure (program spec S5). Mirrors log-core's GitEventPayload.
 *
 * ## Why the graph is recorded rather than shipped
 *
 * Gradescope delivers no `.git`, and a `.git` that did travel would prove less than it
 * appears to: `commit --amend`, `rebase`, and `filter-branch` rewrite history after the
 * fact, so a repository handed in at submission time is evidence of what a student ended up
 * with, not of what happened. The recorder sits on the live repository while the work is
 * being done, so capturing the graph here puts it inside the signed hash chain at the
 * instant it existed, where it can no longer be rewritten.
 *
 * ## No author identity. Ever.
 *
 * There is deliberately no `author_name`, no `author_email`, no author date and no commit
 * message here, and none anywhere else in the log. The approved CPHS protocol treats a new
 * category of identifier as requiring a filed modification BEFORE implementation, and git
 * author identity is exactly that — a real name and a real email address, in clear, attached
 * to every commit. `sha`, `parents`, and `branch` are structural: they describe the SHAPE of
 * the history, not who produced it.
 *
 * Attribution already has a designed home, and it is opaque on purpose: the `student_ref`
 * UUID inside `session.start.identity`. Adding an author field here would reintroduce,
 * unsigned and unreviewed, precisely the identifier that design went to some trouble to
 * avoid.
 *
 * ## Every new field is optional, permanently
 *
 * 1.x bundles, and the 2.0 bundles recorded before this landed, carry only [operation] and
 * [commitSha]. 1.x support is permanent (program spec §9), so these stay optional rather
 * than becoming required at some future version.
 */
data class GitEventPayload(
    val operation: String,
    /**
     * Superseded by [sha], which means the same thing. Retained — and still EMITTED by 2.0
     * writers — so 1.x readers keep working through the reader-before-writer migration
     * (program spec §9).
     */
    val commitSha: String?,
    /** Full 40-char hex sha of the commit HEAD points at. Absent if unreadable. */
    val sha: String? = null,
    /**
     * Parent shas of [sha], in git's own order — the FIRST parent is the branch that was
     * merged into. Order is therefore meaningful and must NEVER be sorted: reversing it
     * inverts the meaning of a merge, and JCS canonicalizes object keys but leaves array
     * elements alone, so a sort here changes the signed bytes and the chain hash.
     *
     * Length is the structure: 0 is a root commit, 1 an ordinary commit, 2 or more a merge.
     * An EMPTY LIST and an ABSENT FIELD mean different things — `[]` is "this commit
     * genuinely has no parents", absent is "the recorder could not read them" — so a reader
     * must not collapse the two, and neither may a writer.
     */
    val parents: List<String>? = null,
    /** Current branch name. Absent when HEAD is detached; never invented. */
    val branch: String? = null,
    /**
     * The REPOSITORY DISCRIMINATOR (decision D12): the root-commit sha of the repository this
     * observation came from, lowercase hex, 40 for sha-1 or 64 for sha-256.
     *
     * A scope can observe more than one repository — a submodule, or a repository nested
     * inside the one that owns the assignment root — and their sha spaces are unrelated, so a
     * reader that keys observed commits by sha alone merges two graphs that have nothing to do
     * with each other. This is what lets the analyzer key on `(repository, sha)`.
     *
     * **OMITTED, never `null`.** Absence is a legal, permanent, blameless answer — a shallow
     * clone, an older recorder, any failure at all — and an absent key canonicalizes
     * differently from an explicit `null`, so the two chain to different hashes exactly as
     * `parents: []` and an absent `parents` do. Readers accept `null` as absence so a
     * nonconforming log still parses; a writer that emits it is nonconforming.
     *
     * Never the repository path and never a remote URL (S14(b)) — a path is arguably an
     * identifier and a remote URL embeds the org and often the student's own username. The
     * writer validates every candidate through [readRepositoryDiscriminator] for exactly that
     * reason.
     */
    val rootCommitSha: String? = null,
)

fun GitEventPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("operation", operation)
    if (commitSha != null) put("commit_sha", commitSha)
    if (sha != null) put("sha", sha)
    // `parents != null` and not `isNotEmpty()`: an empty list is a positive claim of "root
    // commit" and must survive to the wire as `[]`.
    if (parents != null) {
        putJsonArray("parents") {
            for (p in parents) add(JsonPrimitive(p))
        }
    }
    if (branch != null) put("branch", branch)
    // OMITTED when unknown, never `null` — see the field's KDoc. `if (x != null)` and not a
    // `put(k, x)` with a nullable overload, so absence can never be spelled as JsonNull.
    if (rootCommitSha != null) put(REPOSITORY_DISCRIMINATOR_FIELD, rootCommitSha)
}

/**
 * clock.skew payload (recorder PRD §4.2: "Wall clock jumps non-monotonically — delta_ms").
 * Mirrors log-core's ClockSkewPayload (events.ts:193-195) and the VS Code clock-watcher.ts.
 * [deltaMs] is (wall elapsed − monotonic elapsed) since the last reference point: positive
 * when the wall clock jumped forward relative to the monotonic clock, negative when backward.
 */
data class ClockSkewPayload(val deltaMs: Long)

fun ClockSkewPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("delta_ms", deltaMs)
}

/**
 * session.resumed payload. Emitted by [dev.provenance.recorder.wiring.Heartbeat] when a
 * heartbeat tick's wall-clock gap since the previous tick is at least twice the heartbeat
 * interval — the signature of the OS having suspended the process (lid close / sleep) rather
 * than a merely slow tick. Fixes the false `gap_in_heartbeats` cross-submission flags a
 * suspend produces: with no signal marking the gap as an expected suspend rather than a
 * dropped/tampered recorder, the analyzer cannot tell the two apart.
 *
 * [gapMs] is the observed wall-clock delta between this tick and the previous one;
 * [expectedIntervalMs] is the configured heartbeat interval (30_000 in production) the gap is
 * measured against. Deliberately wall-clock, not monotonic — see Heartbeat.kt's tick() for why.
 */
data class SessionResumedPayload(val gapMs: Long, val expectedIntervalMs: Long)

fun SessionResumedPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("gap_ms", gapMs)
    put("expected_interval_ms", expectedIntervalMs)
}

/**
 * One entry in an ext.snapshot's `extensions` array. On the JetBrains host these are
 * installed IntelliJ *plugins*, but the wire field stays `extensions` (and each entry's
 * keys stay id/version/enabled) because that is the log-core contract — the analyzer is
 * host-agnostic. Do NOT rename to "plugins".
 */
data class ExtSnapshotEntry(val id: String, val version: String, val enabled: Boolean)

/**
 * ext.snapshot payload (recorder PRD §4.4). Mirrors log-core's ExtSnapshotPayload
 * (events.ts:128-130) and the VS Code extension-snapshot.ts. Emitted at session start
 * and periodically thereafter.
 */
data class ExtSnapshotPayload(val extensions: List<ExtSnapshotEntry>)

fun ExtSnapshotPayload.toJsonObject(): JsonObject = buildJsonObject {
    put(
        "extensions",
        buildJsonArray {
            for (e in extensions) {
                add(
                    buildJsonObject {
                        put("id", e.id)
                        put("version", e.version)
                        put("enabled", e.enabled)
                    },
                )
            }
        },
    )
}

/**
 * ext.activate payload (recorder PRD §4.2: "Another extension activates while we're recording").
 * Mirrors log-core's ExtActivatePayload (events.ts:132-135) and the VS Code extension-activation.ts.
 * On the JetBrains host this is emitted when a *plugin* is dynamically loaded mid-session; the
 * wire keys stay id/version (host-agnostic contract). Do NOT rename to "plugin".
 */
data class ExtActivatePayload(val id: String, val version: String)

fun ExtActivatePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("id", id)
    put("version", version)
}

/**
 * recorder.degraded payload (recorder PRD §4.8). Mirrors log-core's RecorderDegradedPayload
 * (events.ts:209-211). Emitted once when the recorder transitions into disk-full degraded
 * mode; [reason] is currently always "disk_full" (any write error is treated as disk-full
 * for v1, matching disk-full-handler.ts).
 */
data class RecorderDegradedPayload(val reason: String)

fun RecorderDegradedPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("reason", reason)
}

/**
 * recorder.recovered_from_corruption payload (recorder PRD §4.6, §4.8). Mirrors log-core's
 * RecorderRecoveredFromCorruptionPayload (events.ts:213-215). Emitted into the new session
 * when a prior session's .slog failed to validate and was quarantined; [quarantinedPath] is
 * where the corrupt file was moved (`<slog>.corrupt-<ISO>`) so the analyzer can inspect it.
 */
data class RecorderRecoveredFromCorruptionPayload(val quarantinedPath: String)

fun RecorderRecoveredFromCorruptionPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("quarantined_path", quarantinedPath)
}
