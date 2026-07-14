package dev.provenance.recorder.paste

import dev.provenance.core.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PastePayloadBuilderTest {
    @Test
    fun `short text is inlined fully`() {
        val fields = buildPastePayload("hello world")
        assertEquals("hello world", fields.content)
        assertNull(fields.contentHead)
        assertNull(fields.contentTail)
        assertEquals(Sha256.hex("hello world"), fields.sha256)
        assertEquals(11L, fields.length)
    }

    @Test
    fun `text over 4096 bytes is truncated to head and tail`() {
        val big = "a".repeat(5000)
        val fields = buildPastePayload(big)
        assertNull(fields.content)
        assertNotNull(fields.contentHead)
        assertNotNull(fields.contentTail)
        assertEquals(512, fields.contentHead!!.length)
        assertEquals(512, fields.contentTail!!.length)
        assertEquals(5000L, fields.length)
    }

    @Test
    fun `length is UTF-8 byte length, not char count`() {
        val text = "😀😀" // two U+1F600, 4 UTF-8 bytes each
        val fields = buildPastePayload(text)
        assertEquals(8L, fields.length)
    }

    @Test
    fun `boundary at exactly 4096 bytes still inlines`() {
        val exact = "b".repeat(4096)
        val fields = buildPastePayload(exact)
        assertNotNull(fields.content)
        assertNull(fields.contentHead)
    }
}
