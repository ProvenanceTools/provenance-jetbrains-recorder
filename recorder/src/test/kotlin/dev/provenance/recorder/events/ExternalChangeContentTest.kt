package dev.provenance.recorder.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit 4 test — mirrors events/external-change-content.test.ts.
 *
 * The cross-language vector (external-change-content.json, generated from the
 * monorepo TS by tools/export-conformance-vectors.ts) is the load-bearing test: it
 * pins both the CAP VALUE (64 KB of UTF-8, raised from 4 KB) and the deliberate unit
 * mismatch — the inline/truncate GATE is UTF-8 byte length, but the head/tail SLICE
 * is UTF-16 code units. Emoji-heavy fixtures make byte-length ≠ code-unit-length so a
 * silent byte/char mixup fails here.
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
    fun `the cap is 64 KB`() {
        assertEquals(64 * 1024, MAX_INLINE_BYTES)
    }

    @Test
    fun `text just over the cap in UTF-8 bytes truncates to head plus tail, size is byte count`() {
        // "€" is 3 UTF-8 bytes, 1 UTF-16 code unit. 21846 of them = 65538 bytes > 65536,
        // but only 21846 code units — proves the gate is byte-based, not char-based.
        val text = "€".repeat(21846)
        assertEquals(65538, text.toByteArray(Charsets.UTF_8).size)
        assertEquals(21846, text.length)
        assertTrue("char length must stay under the cap", text.length < MAX_INLINE_BYTES)
        val f = buildExternalChangeContent(text)
        assertEquals(65538, f.newContentSize) // byte count, not char count
        assertNull(f.newContent)
        assertEquals("€".repeat(512), f.newContentHead) // 512 code units
        assertEquals("€".repeat(512), f.newContentTail)
    }

    @Test
    fun `exactly at the cap still inlines (boundary is inclusive)`() {
        val text = "a".repeat(MAX_INLINE_BYTES)
        val f = buildExternalChangeContent(text)
        assertEquals(MAX_INLINE_BYTES, f.newContentSize)
        assertEquals(text, f.newContent)
        assertNull(f.newContentHead)
    }

    @Test
    fun `just below the cap inlines`() {
        val text = "a".repeat(MAX_INLINE_BYTES - 1)
        val f = buildExternalChangeContent(text)
        assertEquals(MAX_INLINE_BYTES - 1, f.newContentSize)
        assertEquals(text, f.newContent)
        assertNull(f.newContentHead)
    }

    @Test
    fun `one byte over the cap truncates`() {
        val text = "a".repeat(MAX_INLINE_BYTES + 1)
        val f = buildExternalChangeContent(text)
        assertEquals(MAX_INLINE_BYTES + 1, f.newContentSize)
        assertNull(f.newContent)
        assertEquals("a".repeat(HEAD_TAIL_CHARS), f.newContentHead)
    }

    @Test
    fun `matches the cross-language monorepo vectors byte-for-byte`() {
        val json = ExternalChangeContentTest::class.java
            .getResourceAsStream("/conformance/external-change-content.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(json) as JsonArray
        assertTrue("expected the generated vector set", cases.size >= 6)

        var inlined = 0
        var truncated = 0

        for ((idx, element) in cases.withIndex()) {
            val case = element as JsonObject
            val input = case["input"]!!.jsonPrimitive.content
            val expected = case["expected"]!!.jsonObject
            val label = "case $idx (${case["note"]?.jsonPrimitive?.content})"

            fun field(name: String): String? =
                expected[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

            val f = buildExternalChangeContent(input)
            assertEquals(
                label,
                expected["new_content_size"]!!.jsonPrimitive.content.toInt(),
                f.newContentSize,
            )
            assertEquals(label, field("new_content"), f.newContent)
            assertEquals(label, field("new_content_head"), f.newContentHead)
            assertEquals(label, field("new_content_tail"), f.newContentTail)

            if (f.newContent != null) inlined++ else truncated++
        }

        // The vector set is only meaningful if it exercises both branches.
        assertTrue("vectors must cover the inline branch", inlined > 0)
        assertTrue("vectors must cover the truncate branch", truncated > 0)
    }
}
