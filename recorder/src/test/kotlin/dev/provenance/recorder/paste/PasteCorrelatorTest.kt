package dev.provenance.recorder.paste

import dev.provenance.core.DocChangeDelta
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.recorder.events.MAX_INLINE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- size gate on paste routing (PRD §4.3) ----------------------------
    //
    // A `paste` payload above MAX_INLINE_BYTES carries only head/tail, so the
    // analyzer's applyPaste returns applied=false and reconstruction for that file
    // dies from that point. Such an insert must route to doc.change instead, whose
    // deltas always replay. Nothing is lost: analysis-core treats a doc.change with
    // source='paste_likely'/'paste_confirmed' as a candidate paste.

    @Test
    fun `single-range paste just under the cap is EmitPaste with full inline content`() {
        val c = PasteCorrelator(getNow = { 0L })
        val text = "a".repeat(MAX_INLINE_BYTES - 1)
        val decision = c.onDocChange(listOf(delta(text)))

        assertTrue(decision is PasteDecision.EmitPaste)
        val fields = (decision as PasteDecision.EmitPaste).fields
        assertEquals(text, fields.content) // replayable: full text present
        assertEquals((MAX_INLINE_BYTES - 1).toLong(), fields.length)
    }

    @Test
    fun `single-range paste exactly at the cap is still EmitPaste (boundary inclusive)`() {
        val c = PasteCorrelator(getNow = { 0L })
        val text = "a".repeat(MAX_INLINE_BYTES)
        val decision = c.onDocChange(listOf(delta(text)))

        assertTrue(decision is PasteDecision.EmitPaste)
        assertEquals(text, (decision as PasteDecision.EmitPaste).fields.content)
    }

    @Test
    fun `single-range paste just over the cap is EmitDocChange paste_likely, not EmitPaste`() {
        val c = PasteCorrelator(getNow = { 0L })
        val text = "a".repeat(MAX_INLINE_BYTES + 1)
        val decision = c.onDocChange(listOf(delta(text)))

        // Must NOT be a paste — that payload would be truncated and unreplayable.
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
        // Sanity: the payload we avoided building really would have lost its content.
        assertNull(buildPastePayload(text).content)
    }

    /**
     * The gate is UTF-8 BYTES, not String.length. 21846 euro signs are 65538 bytes
     * (over the cap) but only 21846 UTF-16 code units — a length-based gate would
     * wrongly emit an unreplayable paste here.
     */
    @Test
    fun `the size gate is on UTF-8 bytes, not string length`() {
        val c = PasteCorrelator(getNow = { 0L })
        val text = "\u20AC".repeat(21846)
        assertEquals(65538, text.toByteArray(Charsets.UTF_8).size)
        assertTrue("char length must stay under the cap", text.length < MAX_INLINE_BYTES)

        val decision = c.onDocChange(listOf(delta(text)))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `multibyte paste at the cap in bytes is still EmitPaste`() {
        val c = PasteCorrelator(getNow = { 0L })
        val text = "\u20AC".repeat(MAX_INLINE_BYTES / 3) // 65535 bytes, under the cap
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= MAX_INLINE_BYTES)

        val decision = c.onDocChange(listOf(delta(text)))
        assertTrue(decision is PasteDecision.EmitPaste)
        assertEquals(text, (decision as PasteDecision.EmitPaste).fields.content)
    }

    @Test
    fun `the shape branch is unchanged - a multi-delta edit under the cap is still EmitDocChange`() {
        val c = PasteCorrelator(getNow = { 0L })
        val decision = c.onDocChange(listOf(delta("a".repeat(40)), delta("b".repeat(40))))

        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }
}
