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
