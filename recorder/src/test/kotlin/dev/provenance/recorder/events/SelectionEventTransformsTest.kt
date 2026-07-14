package dev.provenance.recorder.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JUnit 4 test of the caret/selection → payload transform (no IntelliJ types). */
class SelectionEventTransformsTest {
    @Test
    fun `cursor move builds an empty range with was_selection false`() {
        val p = buildSelectionChangePayload("hw.py", 2, 5, 2, 5, wasSelection = false)
        assertEquals("hw.py", p.path)
        assertEquals(2L, p.range.start.line)
        assertEquals(5L, p.range.start.character)
        assertEquals(2L, p.range.end.line)
        assertEquals(5L, p.range.end.character)
        assertFalse(p.wasSelection)
    }

    @Test
    fun `selection builds the extent range with was_selection true`() {
        val p = buildSelectionChangePayload("hw.py", 0, 0, 1, 3, wasSelection = true)
        assertEquals(0L, p.range.start.line)
        assertEquals(1L, p.range.end.line)
        assertEquals(3L, p.range.end.character)
        assertTrue(p.wasSelection)
    }
}
