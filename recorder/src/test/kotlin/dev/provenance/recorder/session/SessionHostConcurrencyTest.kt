package dev.provenance.recorder.session

import dev.provenance.core.ChainCheck
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.validateChain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The chain must survive concurrent emitters.
 *
 * `emit` is called from more than one thread in production: document, selection and paste
 * events arrive on the EDT, the heartbeat and clock-skew watcher tick on a scheduler thread,
 * and the git wiring emits from its own executor after reading the commit graph. Without a
 * critical section around read-chain-advance, two emitters read the same `prevHash` and the
 * loser writes an entry whose `prev_hash` does not match its predecessor's `hash`.
 *
 * That is indistinguishable from a deleted or tampered entry to validation — so the bug does
 * not merely lose data, it manufactures a tamper finding against a student who did nothing
 * but type while a heartbeat fired. This test failed reliably before `emit` was synchronized.
 */
class SessionHostConcurrencyTest {

    @Test
    fun `concurrent emits keep the chain valid and the sequence dense`() {
        val entries = Collections.synchronizedList(mutableListOf<HashedEnvelope>())
        val host = createSessionHost("sess-concurrent", FixedClock(0)) { entries += it }

        val threads = 8
        val perThread = 60
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    host.emit("doc.change", buildJsonObject { put("t", "$t-$i") })
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("emitters must finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        val ordered = entries.toList()
        assertEquals(threads * perThread, ordered.size)

        // Entries reach onEntry in the SAME order they were chained — the lock is held across
        // onEntry precisely so the .slog is not out of order even when the hashes are right.
        assertEquals(ordered.indices.map { it.toLong() }, ordered.map { it.seq })
        assertEquals(ChainCheck.Valid, validateChain(ordered))
    }
}
