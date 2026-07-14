package dev.provenance.recorder.paste

/**
 * Reformat-tolerant, cheap similarity check between what was on the clipboard at
 * paste time and what actually landed in the Document. IntelliJ's
 * PasteHandler.doPasteAction() runs CopyPastePostProcessors + REFORMAT_ON_PASTE
 * re-indentation + line-ending conversion between the clipboard read and the
 * document insert, so a byte-exact comparison would false-negative on nearly every
 * real code paste. This normalizes line endings + collapses horizontal-whitespace
 * runs (the dimension reformat mostly touches — indentation), then does a
 * length-ratio-weighted containment check rather than O(n*m) edit distance.
 *
 * TUNE AT EXECUTION: PASTE_CONFIRM_SIMILARITY_THRESHOLD is a starting value, not
 * measured against real paste samples across languages. Revisit once runIde manual
 * testing or real submission data is available.
 */
const val PASTE_CONFIRM_SIMILARITY_THRESHOLD = 0.7

object PasteTextSimilarity {
    private val WHITESPACE_RUN = Regex("[ \t]+")

    /**
     * Normalize line endings, then trim + collapse horizontal-whitespace runs
     * PER LINE. Per-line trimming is what makes this reformat-tolerant: IntelliJ's
     * REFORMAT_ON_PASTE re-indents inserted lines, so a leading-whitespace-only
     * difference (the dominant reformat effect) must not count against similarity.
     * A collapse-only normalize would leave a space after each newline and break
     * substring containment for the exact re-indent case this exists to tolerate.
     */
    private fun normalize(text: String): String =
        text.replace("\r\n", "\n")
            .split('\n')
            .joinToString("\n") { line -> line.replace(WHITESPACE_RUN, " ").trim() }
            .trim()

    /**
     * 0.0..1.0. 1.0 only for exact match after normalization. For a containment
     * match (the common reformat case — inserted text is the clipboard text plus
     * added indentation), scores by the length ratio of the shorter (original) to
     * the longer (reformatted) text.
     */
    fun similarity(clipboardText: String, insertedText: String): Double {
        val a = normalize(clipboardText)
        val b = normalize(insertedText)
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        if (a == b) return 1.0

        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        return if (longer.contains(shorter)) {
            shorter.length.toDouble() / longer.length
        } else {
            0.0
        }
    }
}
