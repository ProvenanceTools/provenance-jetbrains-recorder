package dev.provenance.recorder.wiring.snapshot

import dev.provenance.core.ExtSnapshotEntry
import dev.provenance.core.ExtSnapshotPayload
import dev.provenance.recorder.io.FlushScheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pure JUnit 4 — the scheduler is injected as a no-op and the plugin enumeration is
 * injected, so immediate-emit / tick / dispose are tested without a live IDE or
 * PluginManagerCore.
 */
class PluginSnapshotWiringTest {
    private class NoopFuture : ScheduledFuture<Any?> {
        @Volatile var cancelled = false
        override fun cancel(mayInterrupt: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled() = cancelled
        override fun isDone() = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: java.util.concurrent.Delayed?) = 0
    }

    private val noopFuture = NoopFuture()
    private val noopScheduler = FlushScheduler { _, _ -> noopFuture }

    @Test
    fun `emits an immediate ext_snapshot on construction with the injected plugin list`() {
        val emitted = mutableListOf<ExtSnapshotPayload>()
        PluginSnapshotWiring(
            emit = { emitted.add(it) },
            getPlugins = { listOf(ExtSnapshotEntry("a.b.c", "1.0", true)) },
            scheduler = noopScheduler,
        )
        assertEquals(1, emitted.size)
        assertEquals(1, emitted[0].extensions.size)
        assertEquals("a.b.c", emitted[0].extensions[0].id)
    }

    @Test
    fun `tick re-enumerates plugins each call`() {
        var toReturn = listOf(ExtSnapshotEntry("a", "1", true))
        val emitted = mutableListOf<ExtSnapshotPayload>()
        val wiring = PluginSnapshotWiring(
            emit = { emitted.add(it) },
            getPlugins = { toReturn },
            scheduler = noopScheduler,
        )
        // One immediate emit from construction.
        assertEquals(1, emitted.size)
        toReturn = listOf(ExtSnapshotEntry("a", "1", true), ExtSnapshotEntry("b", "2", false))
        wiring.tick()
        assertEquals(2, emitted.size)
        assertEquals(2, emitted[1].extensions.size)
    }

    @Test
    fun `dispose cancels the schedule and is idempotent`() {
        val wiring = PluginSnapshotWiring(
            emit = { },
            getPlugins = { emptyList() },
            scheduler = noopScheduler,
        )
        wiring.dispose()
        assertEquals(true, noopFuture.cancelled)
        wiring.dispose() // must not throw on double-dispose
    }
}
