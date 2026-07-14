package dev.provenance.recorder.events

import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JUnit 4 test — mirrors events/external-change-detector.test.ts. */
class ExternalChangeClassifierTest {
    @Test
    fun `matching hashes classify as clean save`() {
        val expected = ExpectedContent("hello")
        val result = classifySavedContent(expected, "hello")
        result as ExternalChangeResult.CleanSave
        assertEquals(Sha256.hex("hello"), result.newHash)
    }

    @Test
    fun `mismatched hashes classify as changed with old from EXPECTED and new from DISK`() {
        // Regression test for the direction CLAUDE.md warns about: old_hash must be
        // the expected (editor) model's hash, new_hash must be the on-disk hash.
        // Swapping them would make an external Claude-Code-CLI edit look like the
        // student's own typed baseline and the CLI's output look like "what the
        // editor expected" — silently inverting the anti-CLI signal (PRD §4.5, G3).
        val expected = ExpectedContent("editor believes this")
        val onDisk = "but disk actually has this"
        val result = classifySavedContent(expected, onDisk)
        result as ExternalChangeResult.Changed
        assertEquals(Sha256.hex("editor believes this"), result.oldHash)
        assertEquals(Sha256.hex("but disk actually has this"), result.newHash)
        assertTrue(result.oldHash != result.newHash)
    }

    @Test
    fun `diff_size is the absolute code-unit length difference, not byte length`() {
        val expected = ExpectedContent("aaaa") // 4 chars
        val result = classifySavedContent(expected, "aa") as ExternalChangeResult.Changed
        assertEquals(2, result.diffSize)
    }

    @Test
    fun `diff_size uses UTF-16 code-unit length not UTF-8 byte length`() {
        // "€" is 1 code unit but 3 UTF-8 bytes. Expected "€€" (2 code units), disk "€"
        // (1 code unit): code-unit diff = 1, byte diff would be 3. Must be 1.
        val expected = ExpectedContent("€€")
        val result = classifySavedContent(expected, "€") as ExternalChangeResult.Changed
        assertEquals(1, result.diffSize)
    }

    @Test
    fun `classifySavedContent does not mutate the expected model`() {
        val expected = ExpectedContent("original")
        classifySavedContent(expected, "different")
        assertEquals("original", expected.content) // caller resets, not the classifier
    }
}
