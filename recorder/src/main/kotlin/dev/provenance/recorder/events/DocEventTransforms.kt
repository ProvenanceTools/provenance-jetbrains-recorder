package dev.provenance.recorder.events

import dev.provenance.core.DocChangeDelta
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.Position
import dev.provenance.core.Range

/**
 * Pure transformers: raw editor inputs → core payloads. NO IntelliJ types anywhere
 * in this file (CLAUDE.md: "test the event-to-log-entry transformation as a pure
 * function, separately from the platform wiring"). Mirrors doc-events.ts.
 */

/**
 * Max byte-length for inlining doc.open content. Files larger than this carry only
 * sha256/line_count with truncated=true so the analyzer taints reconstruction.
 *
 * Aliases the shared [MAX_INLINE_BYTES] (InlineContentLimits.kt). doc.open was always
 * 64 KB; `paste` and `fs.external_change` were raised to match it, and all three now
 * move together.
 */
const val DOC_OPEN_MAX_INLINE_BYTES: Int = MAX_INLINE_BYTES

/**
 * Inline [text] as content when its UTF-8 byte length is <= [maxInlineBytes];
 * otherwise content = null and truncated = true. Byte length (not char length) is
 * the gate — guards the exact multi-byte-UTF-8 bug transformDocOpen guards against.
 */
fun buildDocOpenPayload(
    path: String,
    sha256: String,
    lineCount: Long,
    text: String,
    maxInlineBytes: Int = DOC_OPEN_MAX_INLINE_BYTES,
): DocOpenPayload {
    val byteLen = text.toByteArray(Charsets.UTF_8).size
    return if (byteLen <= maxInlineBytes) {
        DocOpenPayload(path = path, sha256 = sha256, lineCount = lineCount, content = text, truncated = null)
    } else {
        DocOpenPayload(path = path, sha256 = sha256, lineCount = lineCount, content = null, truncated = true)
    }
}

/**
 * Single-range delta builder. Task 9's DocumentListener seam calls this with
 * pre-change coordinates captured in beforeDocumentChange.
 */
fun buildDocChangeDelta(
    startLine: Long,
    startChar: Long,
    endLine: Long,
    endChar: Long,
    insertedText: String,
): DocChangeDelta = DocChangeDelta(Range(Position(startLine, startChar), Position(endLine, endChar)), insertedText)

/**
 * Always a single-element deltas list in this plan: IntelliJ's DocumentEvent maps
 * 1:1 to one delta (see Task 9 design note), unlike VS Code's multi-delta event.
 * source is always "typed" in Plan 4 (no paste classifier yet).
 */
fun buildDocChangePayload(path: String, delta: DocChangeDelta, source: String = "typed"): DocChangePayload =
    DocChangePayload(path = path, deltas = listOf(delta), source = source)

fun buildDocSavePayload(path: String, sha256: String): DocSavePayload = DocSavePayload(path = path, sha256 = sha256)

fun buildDocClosePayload(path: String): DocClosePayload = DocClosePayload(path = path)
