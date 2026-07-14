package dev.provenance.recorder.failure

import dev.provenance.core.HashedEnvelope
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pure logic — JUnit 4. */
class DiskFullHandlerTest {
    private val zeros = "0".repeat(64)

    private fun entry(kind: String, seq: Long = 0): HashedEnvelope =
        HashedEnvelope(seq, 0, "2026-07-14T00:00:00.000Z", kind, buildJsonObject { }, zeros, zeros)

    private fun handler(
        ringCapacity: Int = 256,
        onDegraded: (String) -> Unit = {},
        notify: (String) -> Unit = {},
    ) = DiskFullHandler(ringCapacity, onDegraded, notify)

    @Test
    fun `degraded is false initially and true after one write error`() {
        val h = handler()
        assertFalse(h.degraded)
        h.handleWriteError(IOException("No space left on device"))
        assertTrue(h.degraded)
    }

    @Test
    fun `handleWriteError is idempotent - notify and onDegraded fire exactly once`() {
        var notifyCount = 0
        var degradedCount = 0
        val h = handler(onDegraded = { degradedCount++ }, notify = { notifyCount++ })
        h.handleWriteError(IOException("full"))
        h.handleWriteError(IOException("still full"))
        h.handleWriteError(IOException("really full"))
        assertEquals(1, notifyCount)
        assertEquals(1, degradedCount)
    }

    @Test
    fun `onDegraded receives disk_full reason`() {
        var reason: String? = null
        val h = handler(onDegraded = { reason = it })
        h.handleWriteError(IOException("full"))
        assertEquals("disk_full", reason)
    }

    @Test
    fun `before degraded enqueue always returns false regardless of kind`() {
        val h = handler()
        assertFalse(h.enqueue(entry("session.start")))
        assertFalse(h.enqueue(entry("doc.change")))
        assertTrue(h.snapshot().isEmpty())
    }

    @Test
    fun `after degraded only critical kinds are buffered`() {
        val h = handler()
        h.handleWriteError(IOException("full"))
        assertTrue(h.enqueue(entry("session.end")))
        assertFalse(h.enqueue(entry("doc.change")))
        assertEquals(listOf("session.end"), h.snapshot().map { it.kind })
    }

    @Test
    fun `ring evicts oldest first when at capacity`() {
        val h = handler(ringCapacity = 2)
        h.handleWriteError(IOException("full"))
        h.enqueue(entry("session.start", seq = 1))
        h.enqueue(entry("recorder.degraded", seq = 2))
        h.enqueue(entry("session.end", seq = 3))
        assertEquals(listOf(2L, 3L), h.snapshot().map { it.seq })
    }

    @Test
    fun `snapshot returns a copy - mutating it does not affect internal state`() {
        val h = handler()
        h.handleWriteError(IOException("full"))
        h.enqueue(entry("session.end", seq = 1))
        val snap = h.snapshot().toMutableList()
        snap.clear()
        assertEquals(1, h.snapshot().size)
    }

    @Test
    fun `all six critical kinds are recognized`() {
        assertEquals(
            setOf(
                "session.start",
                "session.end",
                "fs.external_change",
                "chain.broken",
                "recorder.degraded",
                "recorder.recovered_from_corruption",
            ),
            CRITICAL_KINDS,
        )
    }

    @Test
    fun `concurrent handleWriteError and enqueue never exceed ring capacity and do not throw`() {
        val cap = 8
        val h = handler(ringCapacity = cap)
        val threads = 16
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { tid ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    if (i % 50 == 0) h.handleWriteError(IOException("full"))
                    h.enqueue(entry("session.end", seq = (tid * perThread + i).toLong()))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(h.degraded)
        assertTrue("snapshot size ${h.snapshot().size} must not exceed capacity", h.snapshot().size <= cap)
    }
}
