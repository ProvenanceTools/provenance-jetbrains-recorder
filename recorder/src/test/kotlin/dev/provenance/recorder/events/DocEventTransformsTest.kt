package dev.provenance.recorder.events

import dev.provenance.core.Position
import dev.provenance.core.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure transform tests — no IntelliJ types, so this runs as a plain JUnit 4 unit
 * test (the recorder module runs on JUnit 4; see build.gradle.kts).
 */
class DocEventTransformsTest {
    @Test
    fun `doc open inlines content at exactly the byte limit`() {
        // "€" is 3 UTF-8 bytes. Build a string of exactly maxInlineBytes bytes.
        val max = 9
        val text = "€€€" // 9 bytes
        assertEquals(9, text.toByteArray(Charsets.UTF_8).size)
        val p = buildDocOpenPayload("hw.py", "a".repeat(64), 1, text, maxInlineBytes = max)
        assertEquals(text, p.content)
        assertNull(p.truncated)
    }

    @Test
    fun `doc open truncates one byte over the limit`() {
        val max = 9
        val text = "€€€x" // 10 bytes
        assertEquals(10, text.toByteArray(Charsets.UTF_8).size)
        val p = buildDocOpenPayload("hw.py", "a".repeat(64), 1, text, maxInlineBytes = max)
        assertNull(p.content)
        assertEquals(true, p.truncated)
    }

    @Test
    fun `doc open uses UTF-8 byte length not char length`() {
        // 30 "€" chars = 90 bytes but only 30 chars. With a 64-byte cap it must truncate.
        val text = "€".repeat(30)
        assertEquals(30, text.length)
        assertEquals(90, text.toByteArray(Charsets.UTF_8).size)
        val p = buildDocOpenPayload("hw.py", "a".repeat(64), 1, text, maxInlineBytes = 64)
        assertNull(p.content)
        assertEquals(true, p.truncated)
    }

    @Test
    fun `doc change delta builds expected range and text`() {
        val d = buildDocChangeDelta(2, 4, 2, 9, "hello")
        assertEquals(Range(Position(2, 4), Position(2, 9)), d.range)
        assertEquals("hello", d.text)
    }

    @Test
    fun `doc change payload defaults source to typed with single delta`() {
        val d = buildDocChangeDelta(0, 0, 0, 0, "x")
        val p = buildDocChangePayload("hw.py", d)
        assertEquals("typed", p.source)
        assertEquals(1, p.deltas.size)
        assertEquals("hw.py", p.path)
        assertEquals(d, p.deltas[0])
    }

    @Test
    fun `doc save and close builders`() {
        assertEquals("hw.py", buildDocSavePayload("hw.py", "c".repeat(64)).path)
        assertEquals("c".repeat(64), buildDocSavePayload("hw.py", "c".repeat(64)).sha256)
        assertEquals("hw.py", buildDocClosePayload("hw.py").path)
    }

    @Test
    fun `default inline cap is 64 KB`() {
        assertTrue(DOC_OPEN_MAX_INLINE_BYTES == 64 * 1024)
    }
}
