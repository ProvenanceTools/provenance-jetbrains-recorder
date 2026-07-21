package dev.provenance.recorder.paste

import dev.provenance.core.Sha256
import dev.provenance.recorder.events.MAX_INLINE_BYTES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the cap is 64 KB`() {
        assertEquals(64 * 1024, MAX_INLINE_BYTES)
    }

    @Test
    fun `text over the cap is truncated to head and tail`() {
        val big = "a".repeat(MAX_INLINE_BYTES + 904)
        val fields = buildPastePayload(big)
        assertNull(fields.content)
        assertNotNull(fields.contentHead)
        assertNotNull(fields.contentTail)
        assertEquals(512, fields.contentHead!!.length)
        assertEquals(512, fields.contentTail!!.length)
        assertEquals((MAX_INLINE_BYTES + 904).toLong(), fields.length)
    }

    @Test
    fun `length is UTF-8 byte length, not char count`() {
        val text = "😀😀" // two U+1F600, 4 UTF-8 bytes each
        val fields = buildPastePayload(text)
        assertEquals(8L, fields.length)
    }

    @Test
    fun `boundary at exactly the cap still inlines`() {
        val exact = "b".repeat(MAX_INLINE_BYTES)
        val fields = buildPastePayload(exact)
        assertNotNull(fields.content)
        assertNull(fields.contentHead)
        assertEquals(MAX_INLINE_BYTES.toLong(), fields.length)
    }

    @Test
    fun `just below the cap inlines`() {
        val fields = buildPastePayload("b".repeat(MAX_INLINE_BYTES - 1))
        assertNotNull(fields.content)
        assertNull(fields.contentHead)
    }

    @Test
    fun `one byte over the cap truncates`() {
        val fields = buildPastePayload("b".repeat(MAX_INLINE_BYTES + 1))
        assertNull(fields.content)
        assertNotNull(fields.contentHead)
    }

    /**
     * Multi-byte text that is over the cap in BYTES while still well under it in UTF-16
     * code units — the case that silently inlines if the gate is measured in chars.
     * "€" is 3 UTF-8 bytes, 1 code unit.
     */
    @Test
    fun `multibyte over the cap in bytes but under it in chars still truncates`() {
        val text = "€".repeat(21846) // 65538 bytes, 21846 code units
        assertEquals(65538, text.toByteArray(Charsets.UTF_8).size)
        assertTrue("char length must stay under the cap", text.length < MAX_INLINE_BYTES)
        val fields = buildPastePayload(text)
        assertEquals(65538L, fields.length)
        assertNull(fields.content)
        assertEquals("€".repeat(512), fields.contentHead)
    }

    /**
     * Cross-language vector (paste-payload.json, generated from the monorepo TS by
     * tools/export-conformance-vectors.ts). A `paste` event is not duplicated by a
     * `doc.change`, so a divergence here silently loses evidence in exactly the case
     * the product exists to catch. Never hand-edit the fixture; regenerate it.
     */
    @Test
    fun `matches the cross-language monorepo vectors byte-for-byte`() {
        val json = PastePayloadBuilderTest::class.java
            .getResourceAsStream("/conformance/paste-payload.json")!!
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

            val f = buildPastePayload(input)
            assertEquals(label, expected["length"]!!.jsonPrimitive.content.toLong(), f.length)
            assertEquals(label, expected["sha256"]!!.jsonPrimitive.content, f.sha256)
            assertEquals(label, field("content"), f.content)
            assertEquals(label, field("content_head"), f.contentHead)
            assertEquals(label, field("content_tail"), f.contentTail)

            if (f.content != null) inlined++ else truncated++
        }

        assertTrue("vectors must cover the inline branch", inlined > 0)
        assertTrue("vectors must cover the truncate branch", truncated > 0)
    }
}
