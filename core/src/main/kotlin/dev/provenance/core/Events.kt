package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
 * git.event payload (recorder PRD §4.4). Mirrors log-core's GitEventPayload
 * (events.ts:188-191) and the VS Code git-wiring.ts, which always emits
 * `operation: "state_change"` regardless of the underlying git operation (commit /
 * checkout / branch switch / index change all look the same through the change topic).
 * Matched exactly per this repo's "port the wiring, not a new product" mandate.
 * [commitSha] is optional — omitted when null (no HEAD yet, e.g. an empty repo).
 */
data class GitEventPayload(val operation: String, val commitSha: String?)

fun GitEventPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("operation", operation)
    if (commitSha != null) put("commit_sha", commitSha)
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
