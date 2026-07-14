package dev.provenance.recorder.wiring.paste

import dev.provenance.core.PasteAnomalyPayload
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.paste.PasteCorrelator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Pure JUnit 4 — the scheduler is injected as a no-op so tick()/dispose() are tested directly. */
class PasteAnomalyTickerTest {
    private class NoopFuture : ScheduledFuture<Any?> {
        override fun cancel(mayInterrupt: Boolean) = true
        override fun isCancelled() = false
        override fun isDone() = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: java.util.concurrent.Delayed?) = 0
    }

    private val noopScheduler = FlushScheduler { _, _ -> NoopFuture() }

    @Test
    fun `tick with no discrepancy emits nothing`() {
        val correlator = PasteCorrelator(getNow = { 0L })
        val emitted = mutableListOf<PasteAnomalyPayload>()
        val ticker = PasteAnomalyTicker(correlator, { emitted.add(it) }, scheduler = noopScheduler)

        ticker.tick()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `tick with a discrepancy beyond tolerance emits paste_anomaly deltas`() {
        val correlator = PasteCorrelator(getNow = { 0L })
        val emitted = mutableListOf<PasteAnomalyPayload>()
        val ticker = PasteAnomalyTicker(correlator, { emitted.add(it) }, scheduler = noopScheduler)

        // Two intercepts with no matching large inserts → discrepancy 2 (> default tolerance 1).
        correlator.onPasteActionFired(null)
        correlator.onPasteActionFired(null)
        ticker.tick()

        assertEquals(1, emitted.size)
        assertEquals(PasteAnomalyPayload(interceptedCount = 2, largeInsertCount = 0), emitted[0])
    }

    @Test
    fun `dispose cancels the schedule and is idempotent`() {
        val correlator = PasteCorrelator(getNow = { 0L })
        val ticker = PasteAnomalyTicker(correlator, { }, scheduler = noopScheduler)
        ticker.dispose()
        ticker.dispose() // must not throw on double-dispose
    }
}
