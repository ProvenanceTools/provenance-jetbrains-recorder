package dev.provenance.recorder.wiring

import dev.provenance.core.ClockSkewPayload
import dev.provenance.recorder.io.FlushScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pure JUnit 4 — clocks and scheduler are injected, so drift detection / reset / dispose are
 * tested deterministically with no live IDE (mirrors clock-watcher.test.ts).
 */
class ClockSkewWatcherTest {
    private class NoopFuture : ScheduledFuture<Any?> {
        @Volatile var cancelled = false
        override fun cancel(mayInterrupt: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: java.util.concurrent.Delayed?) = 0
    }

    private val noopFuture = NoopFuture()
    private val noopScheduler = FlushScheduler { _, _ -> noopFuture }

    /** Mutable injected clocks. */
    private var mono = 0L
    private var wall = 0L

    private fun watcher(emitted: MutableList<ClockSkewPayload>): ClockSkewWatcher =
        ClockSkewWatcher(
            emit = { emitted.add(it) },
            getMonotonicMs = { mono },
            getWallMs = { wall },
            scheduler = noopScheduler,
        )

    @Test
    fun `no emit at construction and no emit when clocks advance together`() {
        val emitted = mutableListOf<ClockSkewPayload>()
        val w = watcher(emitted)
        assertEquals("no immediate emit (only reference points captured)", 0, emitted.size)
        // Both clocks advance by the same amount → zero drift.
        mono += 1_000; wall += 1_000
        w.tick()
        assertEquals(0, emitted.size)
    }

    @Test
    fun `emits delta_ms when wall jumps forward beyond threshold`() {
        val emitted = mutableListOf<ClockSkewPayload>()
        val w = watcher(emitted)
        // 1s of monotonic elapsed, but wall jumped 3s → drift = +2000ms.
        mono += 1_000; wall += 3_000
        w.tick()
        assertEquals(1, emitted.size)
        assertEquals(2_000L, emitted[0].deltaMs)
    }

    @Test
    fun `emits negative delta_ms when wall jumps backward`() {
        val emitted = mutableListOf<ClockSkewPayload>()
        val w = watcher(emitted)
        mono += 1_000; wall -= 500 // wall regressed relative to monotonic → drift = -1500ms
        w.tick()
        assertEquals(1, emitted.size)
        assertEquals(-1_500L, emitted[0].deltaMs)
    }

    @Test
    fun `sub-threshold drift does not emit`() {
        val emitted = mutableListOf<ClockSkewPayload>()
        val w = watcher(emitted)
        mono += 1_000; wall += 1_400 // drift = 400ms < 500ms threshold
        w.tick()
        assertEquals(0, emitted.size)
    }

    @Test
    fun `resets reference after a jump so the same drift is not re-emitted`() {
        val emitted = mutableListOf<ClockSkewPayload>()
        val w = watcher(emitted)
        mono += 1_000; wall += 3_000
        w.tick()
        assertEquals(1, emitted.size)
        // Next tick with clocks back in lockstep — no re-emit of the already-reported jump.
        mono += 1_000; wall += 1_000
        w.tick()
        assertEquals(1, emitted.size)
    }

    @Test
    fun `dispose cancels the schedule and is idempotent`() {
        val w = watcher(mutableListOf())
        w.dispose()
        assertTrue(noopFuture.cancelled)
        w.dispose() // must not throw on double-dispose
    }
}
