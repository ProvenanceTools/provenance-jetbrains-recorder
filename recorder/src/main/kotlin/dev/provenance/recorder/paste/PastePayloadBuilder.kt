package dev.provenance.recorder.paste

import dev.provenance.core.PastePayload
import dev.provenance.core.Range
import dev.provenance.core.Sha256

/**
 * Ports packages/recorder/src/events/paste-payload.ts. Stores full pasted text
 * inline up to MAX_INLINE_BYTES; larger pastes store a hash + head/tail
 * truncation. length/truncation are UTF-8 byte-length based (not char count),
 * matching the TS version's Buffer.byteLength usage.
 *
 * PastePayloadFields is the path-agnostic intermediate the PasteCorrelator carries
 * (it decides paste-vs-doc.change before the emit site attaches the workspace path);
 * toPastePayload(path, range) produces the core/ format-contract PastePayload.
 */
const val MAX_INLINE_BYTES = 4096
const val HEAD_TAIL_BYTES = 512

data class PastePayloadFields(
    val length: Long,
    val sha256: String,
    val content: String? = null,
    val contentHead: String? = null,
    val contentTail: String? = null,
)

fun buildPastePayload(text: String): PastePayloadFields {
    val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
    val hashHex = Sha256.hex(text)

    return if (byteLength <= MAX_INLINE_BYTES) {
        PastePayloadFields(length = byteLength, sha256 = hashHex, content = text)
    } else {
        PastePayloadFields(
            length = byteLength,
            sha256 = hashHex,
            contentHead = text.take(HEAD_TAIL_BYTES),
            contentTail = text.takeLast(HEAD_TAIL_BYTES),
        )
    }
}

/** Attach path + range and produce the on-wire core PastePayload (events.ts:95-103). */
fun PastePayloadFields.toPastePayload(path: String, range: Range): PastePayload =
    PastePayload(
        path = path,
        range = range,
        length = length,
        sha256 = sha256,
        content = content,
        contentHead = contentHead,
        contentTail = contentTail,
    )
