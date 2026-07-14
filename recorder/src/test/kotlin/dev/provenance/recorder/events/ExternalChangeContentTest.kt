package dev.provenance.recorder.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JUnit 4 test — mirrors events/external-change-content.test.ts.
 *
 * The cross-language vector (external-change-content.json, generated from the
 * monorepo TS via tsx) is the load-bearing test: it pins the deliberate unit
 * mismatch — the inline/truncate GATE is UTF-8 byte length, but the head/tail
 * SLICE is UTF-16 code units. An emoji-heavy fixture makes byte-length ≠
 * code-unit-length so a silent byte/char mixup fails here.
 */
class ExternalChangeContentTest {
    @Test
    fun `small ASCII text inlines full content, head and tail null`() {
        val text = "hello world"
        val f = buildExternalChangeContent(text)
        assertEquals(11, f.newContentSize) // ASCII: bytes == chars
        assertEquals(text, f.newContent)
        assertNull(f.newContentHead)
        assertNull(f.newContentTail)
    }

    @Test
    fun `text just over 4096 UTF-8 bytes truncates to head plus tail, size is byte count`() {
        // "€" is 3 UTF-8 bytes, 1 UTF-16 code unit. 1366 of them = 4098 bytes > 4096,
        // but only 1366 code units — proves the gate is byte-based, not char-based.
        val text = "€".repeat(1366)
        assertEquals(4098, text.toByteArray(Charsets.UTF_8).size)
        assertEquals(1366, text.length)
        val f = buildExternalChangeContent(text)
        assertEquals(4098, f.newContentSize) // byte count, not char count
        assertNull(f.newContent)
        assertEquals("€".repeat(512), f.newContentHead) // 512 code units
        assertEquals("€".repeat(512), f.newContentTail)
    }

    @Test
    fun `exactly 4096 bytes still inlines (boundary is inclusive)`() {
        val text = "a".repeat(4096)
        val f = buildExternalChangeContent(text)
        assertEquals(4096, f.newContentSize)
        assertEquals(text, f.newContent)
        assertNull(f.newContentHead)
    }

    @Test
    fun `matches the cross-language monorepo vector byte-for-byte`() {
        val json = ExternalChangeContentTest::class.java
            .getResourceAsStream("/conformance/external-change-content.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val v = Json.parseToJsonElement(json) as JsonObject
        val input = v["input"]!!.jsonPrimitive.content
        val expectedSize = v["new_content_size"]!!.jsonPrimitive.content.toInt()
        val expectedHead = v["new_content_head"]!!.jsonPrimitive.content
        val expectedTail = v["new_content_tail"]!!.jsonPrimitive.content

        val f = buildExternalChangeContent(input)
        assertEquals(expectedSize, f.newContentSize)
        assertNull(f.newContent) // large input → head/tail path
        assertEquals(expectedHead, f.newContentHead)
        assertEquals(expectedTail, f.newContentTail)
    }
}
