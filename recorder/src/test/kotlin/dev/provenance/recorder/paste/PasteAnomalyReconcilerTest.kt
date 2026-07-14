package dev.provenance.recorder.paste

import dev.provenance.core.PasteAnomalyPayload
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PasteAnomalyReconcilerTest {
    @Test
    fun `equal deltas within tolerance is no anomaly`() {
        val before = PasteCounterSnapshot(intercepted = 5, largeInsert = 5)
        val after = PasteCounterSnapshot(intercepted = 7, largeInsert = 7)
        assertNull(PasteAnomalyReconciler.check(before, after))
    }

    @Test
    fun `deltas within the default tolerance window of 1 is no anomaly`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 2, largeInsert = 3) // discrepancy 1
        assertNull(PasteAnomalyReconciler.check(before, after))
    }

    @Test
    fun `discrepancy beyond tolerance emits the anomaly payload with raw deltas`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 1, largeInsert = 5) // discrepancy 4
        val result = PasteAnomalyReconciler.check(before, after)
        assertEquals(PasteAnomalyPayload(interceptedCount = 1, largeInsertCount = 5), result)
    }

    @Test
    fun `custom tolerance window is respected`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 0, largeInsert = 3)
        assertNull(PasteAnomalyReconciler.check(before, after, toleranceWindow = 3))
        assertNotNull(PasteAnomalyReconciler.check(before, after, toleranceWindow = 2))
    }

    @Test
    fun `payload json uses snake_case wire field names`() {
        val payload = PasteAnomalyPayload(interceptedCount = 2, largeInsertCount = 9)
        val json = payload.toJsonObject()
        assertEquals(2, json["intercepted_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(9, json["large_insert_count"]!!.jsonPrimitive.content.toInt())
    }
}
