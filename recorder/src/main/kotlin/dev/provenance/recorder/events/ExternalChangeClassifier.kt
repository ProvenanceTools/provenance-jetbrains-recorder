package dev.provenance.recorder.events

import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContent

/**
 * Result of comparing on-disk content against the expected (editor) model.
 * Mirrors external-change-detector.ts's ExternalChangeResult union.
 */
sealed interface ExternalChangeResult {
    data class CleanSave(val newHash: String) : ExternalChangeResult
    data class Changed(val oldHash: String, val newHash: String, val diffSize: Int) : ExternalChangeResult
}

/**
 * Compare on-disk content against the expected (editor) model. Port of
 * compareSavedContent (external-change-detector.ts:44-65).
 *
 * DIRECTION IS FIXED (the mistake CLAUDE.md/PRD §4.5 warn about): old = the expected
 * model (source of truth for "what the editor believes"), new = on-disk reality.
 * Never the reverse. Does NOT mutate [expected] — the caller resets it after emitting
 * fs.external_change so the next comparison chains from reality.
 *
 * diff_size is an approximation: |onDisk.length - expected.content.length| in UTF-16
 * code units (String.length, matching JS .length bit-for-bit). Faithful port: not a
 * real diff, and NOT byte length. Same-length-different-bytes edits yield diff_size 0.
 */
fun classifySavedContent(expected: ExpectedContent, onDiskContent: String): ExternalChangeResult {
    val actualHash = Sha256.hex(onDiskContent)
    val expectedHash = expected.hash
    if (actualHash == expectedHash) return ExternalChangeResult.CleanSave(actualHash)
    val diffSize = kotlin.math.abs(onDiskContent.length - expected.content.length)
    return ExternalChangeResult.Changed(oldHash = expectedHash, newHash = actualHash, diffSize = diffSize)
}
