package dev.provenance.recorder.events

// The inline/truncate byte cap (MAX_INLINE_BYTES, 64 KB) lives in InlineContentLimits.kt,
// shared with the paste builder and doc.open. Same package, so it resolves without an import.

/**
 * Head/tail slice length in UTF-16 code units. Named _CHARS (not _BYTES like the
 * VS Code recorder's HEAD_TAIL_BYTES) because the slice below is code-unit based —
 * the original name is a slight misnomer, noted here and not copied.
 */
const val HEAD_TAIL_CHARS = 512

data class ExternalChangeContentFields(
    val newContentSize: Int,
    val newContent: String?,
    val newContentHead: String?,
    val newContentTail: String?,
)

/**
 * Build the inline-content fields for an fs.external_change payload. Mirrors
 * external-change-content.ts exactly, INCLUDING the unit mismatch:
 *  - the size gate (`new_content_size`, inline-vs-truncate decision) is UTF-8 byte
 *    length (`Buffer.byteLength(text,'utf8')`), and
 *  - the head/tail slice is UTF-16 code units (`text.slice(0,512)` / `.slice(-512)`).
 * This asymmetry is a faithful-port decision, pinned by a cross-language conformance
 * vector — do NOT "fix" it to be consistent.
 */
fun buildExternalChangeContent(text: String): ExternalChangeContentFields {
    val byteLength = text.toByteArray(Charsets.UTF_8).size
    if (byteLength <= MAX_INLINE_BYTES) {
        return ExternalChangeContentFields(byteLength, text, null, null)
    }
    val head = text.substring(0, minOf(HEAD_TAIL_CHARS, text.length))
    val tail = text.substring(maxOf(0, text.length - HEAD_TAIL_CHARS))
    return ExternalChangeContentFields(byteLength, null, head, tail)
}
