package dev.provenance.recorder.paste

import dev.provenance.core.DocChangeDelta
import dev.provenance.core.Position
import dev.provenance.core.Range
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic — JUnit 4 (recorder module runs on JUnit 4; see build.gradle.kts). */
class PasteClassifierTest {
    private fun delta(text: String, sameLineLen: Long = 0L) =
        DocChangeDelta(Range(Position(0, sameLineLen), Position(0, sameLineLen)), text)

    @Test
    fun `empty delta list is typed`() {
        assertEquals(PasteClassification.TYPED, classifyChange(emptyList()))
    }

    @Test
    fun `single delta under threshold is typed`() {
        assertEquals(PasteClassification.TYPED, classifyChange(listOf(delta("x".repeat(29)))))
    }

    @Test
    fun `single delta at or over threshold is paste_likely`() {
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(listOf(delta("x".repeat(30)))))
    }

    @Test
    fun `single large delta with non-empty range is still paste_likely`() {
        val d = DocChangeDelta(Range(Position(0, 0), Position(0, 5)), "y".repeat(40))
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(listOf(d)))
    }

    @Test
    fun `multi-delta aggregate over threshold WITHOUT a newline is typed`() {
        val deltas = (1..10).map { delta("abc") } // 30 chars total, no newlines
        assertEquals(PasteClassification.TYPED, classifyChange(deltas))
    }

    @Test
    fun `multi-delta aggregate over threshold WITH a newline is paste_likely`() {
        val deltas = listOf(delta("a".repeat(20)), delta("b\nc".repeat(4)))
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(deltas))
    }
}
