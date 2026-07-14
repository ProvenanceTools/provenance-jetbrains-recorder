package dev.provenance.recorder.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteTextSimilarityTest {
    @Test
    fun `identical text is 1_0`() {
        assertEquals(1.0, PasteTextSimilarity.similarity("fun f() {}", "fun f() {}"), 0.0)
    }

    @Test
    fun `re-indented text (reformat-on-paste) still scores high`() {
        val clip = "if (x) {\ny = 1\n}"
        val inserted = "    if (x) {\n        y = 1\n    }"
        assertTrue(PasteTextSimilarity.similarity(clip, inserted) >= PASTE_CONFIRM_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `crlf-to-lf line ending conversion does not reduce score`() {
        assertEquals(1.0, PasteTextSimilarity.similarity("line1\r\nline2\r\n", "line1\nline2\n"), 0.0)
    }

    @Test
    fun `unrelated text scores 0`() {
        assertEquals(0.0, PasteTextSimilarity.similarity("completely different content here", "xyz"), 0.0)
    }

    @Test
    fun `empty clipboard and empty inserted text is a degenerate 1_0 match`() {
        assertEquals(1.0, PasteTextSimilarity.similarity("", ""), 0.0)
    }

    @Test
    fun `empty clipboard against non-empty inserted text is 0`() {
        assertEquals(0.0, PasteTextSimilarity.similarity("", "something"), 0.0)
    }
}
