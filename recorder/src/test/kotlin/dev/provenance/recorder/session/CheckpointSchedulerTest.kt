package dev.provenance.recorder.session

import dev.provenance.core.Checkpoint
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.verifyCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic — JUnit 4. Uses kotlinx.coroutines.runBlocking + Dispatchers.Unconfined
 * (both part of the bundled kotlinx-coroutines-core; NOT kotlinx-coroutines-test) for
 * deterministic, immediate execution — see plan Global Constraints.
 */
class CheckpointSchedulerTest {
    private val keypair = generateSessionKeypair()

    private fun scheduler(
        scope: CoroutineScope,
        onError: (Throwable) -> Unit = { throw AssertionError("unexpected onError", it) },
        appendCheckpoint: suspend (Checkpoint) -> Unit,
    ) = CheckpointScheduler(scope, keypair.privateKey, appendCheckpoint, onError)

    @Test
    fun `schedule then drain persists exactly one checkpoint whose signature verifies`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val persisted = mutableListOf<Checkpoint>()
        val s = scheduler(scope) { persisted.add(it) }

        s.schedule(seq = 42L, entryHash = "a".repeat(64))
        s.drain()

        assertEquals(1, persisted.size)
        val cp = persisted.single()
        assertEquals(42L, cp.seq)
        assertEquals("a".repeat(64), cp.hash)
        assertTrue("signature must verify", verifyCheckpoint(cp, keypair.publicKeyHex))
    }

    @Test
    fun `two rapid schedules drain in seq order (the ordering property the Mutex exists for)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val persisted = mutableListOf<Checkpoint>()
        val s = scheduler(scope) { persisted.add(it) }

        s.schedule(seq = 100L, entryHash = "b".repeat(64))
        s.schedule(seq = 200L, entryHash = "c".repeat(64))
        s.drain()

        assertEquals(listOf(100L, 200L), persisted.map { it.seq })
    }

    @Test
    fun `a throwing append reports via onError and does not wedge subsequent schedules`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val errors = mutableListOf<Throwable>()
        val persisted = mutableListOf<Checkpoint>()
        var failNext = true
        val s = scheduler(
            scope,
            appendCheckpoint = { cp ->
                if (failNext) {
                    failNext = false
                    throw RuntimeException("write failed")
                }
                persisted.add(cp)
            },
            onError = { errors.add(it) },
        )

        s.schedule(seq = 1L, entryHash = "d".repeat(64))
        s.drain()
        s.schedule(seq = 2L, entryHash = "e".repeat(64))
        s.drain()

        assertEquals(1, errors.size)
        assertEquals(listOf(2L), persisted.map { it.seq })
    }

    @Test
    fun `drain with nothing ever scheduled returns immediately (no NPE on null lastJob)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val s = scheduler(scope) { /* never called */ }
        s.drain()
    }
}
