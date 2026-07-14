package dev.provenance.recorder.paste

import dev.provenance.core.PasteAnomalyPayload
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.events.buildDocChangePayload
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload-shape parity gate against packages/log-core/src/events.ts. Not a crypto/hash
 * conformance test (paste content isn't deterministic) — asserts field names, snake_case
 * wire keys, and optional-field omission-vs-null exactly match the pinned TS types. A
 * failure here means this plugin would emit a bundle the real analyzer can't parse.
 */
class PasteEventShapeConformanceTest {
    private val range = Range(Position(0, 0), Position(0, 0))

    @Test
    fun `PastePayload json has exactly the events_ts_95-103 fields for a short paste`() {
        val json = buildPastePayload("short text").toPastePayload("a.py", range).toJsonObject()
        assertEquals(setOf("path", "range", "length", "sha256", "content"), json.keys)
        assertFalse(json.containsKey("content_head"))
        assertFalse(json.containsKey("content_tail"))
    }

    @Test
    fun `PastePayload json omits content and includes head-tail for a long paste`() {
        val json = buildPastePayload("x".repeat(5000)).toPastePayload("a.py", range).toJsonObject()
        assertEquals(setOf("path", "range", "length", "sha256", "content_head", "content_tail"), json.keys)
    }

    @Test
    fun `DocChangePayload json has exactly the events_ts_80-84 fields`() {
        val delta = dev.provenance.core.DocChangeDelta(range, "x")
        val json = buildDocChangePayload("a.py", delta, source = "paste_confirmed").toJsonObject()
        assertEquals(setOf("path", "deltas", "source"), json.keys)
    }

    @Test
    fun `DocChangePayload source only ever takes the three pinned enum values`() {
        val c = PasteCorrelator(getNow = { 0L })
        val typed = c.onDocChange(listOf(dev.provenance.core.DocChangeDelta(Range(Position(0, 0), Position(0, 1)), "x")))
        assertTrue(typed is PasteDecision.EmitDocChange)
        assertTrue((typed as PasteDecision.EmitDocChange).source in setOf("typed", "paste_likely", "paste_confirmed"))
    }

    @Test
    fun `PasteAnomalyPayload json has exactly the events_ts_199-202 snake_case fields`() {
        val json = PasteAnomalyPayload(interceptedCount = 2, largeInsertCount = 5).toJsonObject()
        assertEquals(setOf("intercepted_count", "large_insert_count"), json.keys)
        assertEquals(2, json["intercepted_count"]!!.jsonPrimitive.content.toInt())
    }
}
