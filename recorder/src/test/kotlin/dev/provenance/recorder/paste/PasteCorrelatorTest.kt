package dev.provenance.recorder.paste

import dev.provenance.core.DocChangeDelta
import dev.provenance.core.Position
import dev.provenance.core.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteCorrelatorTest {
    /** Empty-range single delta (paste-shaped) unless startChar != endChar. */
    private fun delta(text: String, startChar: Long = 0L, endChar: Long = 0L) =
        DocChangeDelta(Range(Position(0, startChar), Position(0, endChar)), text)

    @Test
    fun `typed change with no pending action is EmitDocChange typed`() {
        val c = PasteCorrelator(getNow = { 0L })
        val decision = c.onDocChange(listOf(delta("x")))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("typed", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `single empty-range paste-shaped delta with no matching action is EmitPaste`() {
        val c = PasteCorrelator(getNow = { 0L })
        val decision = c.onDocChange(listOf(delta("y".repeat(40))))
        assertTrue(decision is PasteDecision.EmitPaste)
    }

    @Test
    fun `action fired then matching doc change within window with matching clipboard is paste_confirmed`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        // Clipboard content matches the inserted text; a non-empty-range delta routes
        // through EmitDocChange (not the single-empty-range paste shape).
        val text = "def foo():\n    return 42\n" + "x".repeat(20)
        c.onPasteActionFired(clipboardText = text)
        now = 10
        val decision = c.onDocChange(listOf(delta(text, startChar = 0L, endChar = 5L)))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_confirmed", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `re-indented paste (reformat-on-paste) still confirms`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        val clip = "if (x) {\ny = 1\n}\n" + "z".repeat(20)
        val reindented = "    if (x) {\n        y = 1\n    }\n" + "z".repeat(20)
        c.onPasteActionFired(clipboardText = clip)
        now = 5
        val decision = c.onDocChange(listOf(delta(reindented, startChar = 0L, endChar = 3L)))
        assertEquals("paste_confirmed", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `action fired but doc change arrives after the tolerance window is unconfirmed`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now }, withinMs = 50)
        val text = "def foo():\n    return 42\n" + "x".repeat(20)
        c.onPasteActionFired(clipboardText = text)
        now = 200 // well past 50ms
        val decision = c.onDocChange(listOf(delta(text, startChar = 0L, endChar = 5L)))
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `action fired with clipboard unlike the inserted text is unconfirmed`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        c.onPasteActionFired(clipboardText = "totally unrelated clipboard content padding here")
        now = 5
        val decision = c.onDocChange(
            listOf(delta("nothing like the clipboard at all here\nmore unrelated padding", startChar = 0L, endChar = 5L)),
        )
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `pending expectation is consumed by one doc change and does not confirm a second`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        val text = "def foo():\n    return 42\n" + "x".repeat(20)
        c.onPasteActionFired(clipboardText = text)
        now = 5
        val first = c.onDocChange(listOf(delta(text, startChar = 0L, endChar = 5L)))
        val second = c.onDocChange(listOf(delta(text, startChar = 0L, endChar = 5L)))
        assertEquals("paste_confirmed", (first as PasteDecision.EmitDocChange).source)
        assertEquals("paste_likely", (second as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `counters track intercepts and large inserts independently`() {
        val c = PasteCorrelator(getNow = { 0L })
        c.onPasteActionFired(clipboardText = null)
        c.onDocChange(listOf(delta("typed"))) // typed, not a large insert
        c.onDocChange(listOf(delta("z".repeat(40)))) // large insert
        assertEquals(1, c.interceptedCount)
        assertEquals(1, c.largeInsertCount)
    }
}
