package dev.provenance.recorder.session

import dev.provenance.core.Checkpoint
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.serializeEntry
import dev.provenance.core.toJsonObject
import dev.provenance.core.verifyCheckpoint
import dev.provenance.recorder.failure.CRITICAL_KINDS
import dev.provenance.recorder.failure.DiskFullHandler
import dev.provenance.recorder.startup.RecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.SlogReadResult
import dev.provenance.recorder.startup.recoverPreviousSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Plan 8's payoff test: exercise the real CheckpointCadence + CheckpointScheduler +
 * DiskFullHandler + chain-recovery logic through the exact composition
 * (routeSessionEntry / prevSessionIdFor / recoveryFollowupPayload) RecordingSessionController
 * wires in production — entirely as pure JUnit, no IntelliJ Platform, no real filesystem for
 * the writer. See docs/plans/2026-07-14-checkpoints-recovery-degraded.md Task 8.
 */
class SessionLifecycleIntegrationTest {
    private val keypair = generateSessionKeypair()

    /**
     * Stands in for SessionWriter: records every entry it successfully "wrote". Can be told
     * to fail starting from a given 1-indexed call, modeling a disk-full write error. Enforces
     * the invariant scenario 5 is about: once it has reported a failure, it must never be
     * called again without an explicit clearFailure() — proving the wiring never retries a
     * write against a writer that just failed.
     */
    private class FakeWriter(private val failFromCall: Int? = null) {
        val appended = mutableListOf<HashedEnvelope>()
        private var calls = 0
        private var awaitingClear = false

        fun append(entry: HashedEnvelope) {
            check(!awaitingClear) {
                "append() invoked again after a failure without clearFailure() — " +
                    "wiring retried against a writer that just failed"
            }
            calls++
            if (failFromCall != null && calls >= failFromCall) {
                awaitingClear = true
                throw IOException("No space left on device")
            }
            appended.add(entry)
        }
    }

    private fun host(onEntry: (HashedEnvelope) -> Unit): SessionHost =
        createSessionHost("sess-lifecycle", FixedClock(), onEntry)

    private fun immediateScheduler(
        persisted: MutableList<Checkpoint>,
        onError: (Throwable) -> Unit = { throw AssertionError("unexpected checkpoint error", it) },
    ): CheckpointScheduler =
        CheckpointScheduler(CoroutineScope(Dispatchers.Unconfined), keypair.privateKey, { persisted.add(it) }, onError)

    // --- Scenario 1: normal run with checkpoints ------------------------------------------

    @Test
    fun `250 entries trigger exactly two checkpoints at seq 99 and 199`() = runBlocking {
        val writer = FakeWriter()
        val cadence = CheckpointCadence()
        val diskFull = DiskFullHandler(onDegraded = {}, notify = {})
        val persisted = mutableListOf<Checkpoint>()
        val scheduler = immediateScheduler(persisted)

        val h = host { entry -> routeSessionEntry(entry, writer::append, diskFull, cadence, scheduler::schedule) }
        repeat(250) { h.emit("doc.change", buildJsonObject { }) }
        scheduler.drain()

        assertEquals(250, writer.appended.size)
        assertEquals("checkpoints at seq 99 and 199 only", listOf(99L, 199L), persisted.map { it.seq })
        for (cp in persisted) {
            assertTrue("checkpoint signature must verify", verifyCheckpoint(cp, keypair.publicKeyHex))
            assertEquals("checkpoint hash must be the entry's own hash", writer.appended[cp.seq.toInt()].hash, cp.hash)
        }
    }

    // --- Scenarios 2 & 3: startup recovery wiring into the new session --------------------

    /** Mirrors ChainRecoveryTest's FakeDeps: in-memory RecoveryDeps, no real filesystem. */
    private class FakeRecoveryDeps(
        override val provenanceDir: String,
        private val files: MutableMap<String, SlogReadResult>,
        private val nowInstant: Instant = Instant.parse("2026-07-14T10:20:30.500Z"),
    ) : RecoveryDeps {
        val renames = mutableListOf<Pair<String, String>>()

        override suspend fun readSlogFile(path: String): SlogReadResult = files[path] ?: SlogReadResult.Err("not_found")

        override suspend fun rename(from: String, to: String) {
            renames.add(from to to)
            files[to] = files.remove(from) ?: SlogReadResult.Err("not_found")
        }

        override suspend fun listSlogFiles(dir: String): List<String> = files.keys.map { it.substringAfterLast('/') }

        override fun now(): Instant = nowInstant
    }

    private fun chainText(vararg kinds: Pair<String, Map<String, String>>): String {
        val collected = mutableListOf<HashedEnvelope>()
        val h = createSessionHost("prior-sess", FixedClock()) { collected.add(it) }
        for ((kind, data) in kinds) {
            h.emit(kind, buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
        }
        return collected.joinToString("") { serializeEntry(it) }
    }

    /** Simulates the exact prev_session_id / recorder.recovered_from_corruption wiring
     * RecordingSessionController performs (Step 2 / Step 7), via the same shared helpers. */
    private fun emitNewSessionOpening(h: SessionHost, recovery: RecoveryDecision) {
        val prevSessionId = prevSessionIdFor(recovery)
        h.emit(
            "session.start",
            buildJsonObject {
                put("session_id", h.sessionId)
                if (prevSessionId != null) put("prev_session_id", prevSessionId)
            },
        )
        recoveryFollowupPayload(recovery)?.let { h.emit("recorder.recovered_from_corruption", it.toJsonObject()) }
    }

    @Test
    fun `dangling prior session yields prev_session_id on the new session start`() = runBlocking {
        val dir = "/prov"
        val deps = FakeRecoveryDeps(
            dir,
            mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(chainText("session.start" to mapOf("session_id" to "prev-456")))),
        )
        val decision = recoverPreviousSession(deps)
        assertEquals(RecoveryDecision.PreviousSessionDangling("prev-456", "$dir/s.slog"), decision)

        val entries = mutableListOf<HashedEnvelope>()
        val h = createSessionHost("new-sess", FixedClock()) { entries.add(it) }
        emitNewSessionOpening(h, decision)

        assertEquals("session.start", entries[0].kind)
        assertEquals("prev-456", entries[0].data["prev_session_id"]?.jsonPrimitive?.content)
        assertTrue("no quarantine for a dangling (not corrupt) session", deps.renames.isEmpty())
    }

    @Test
    fun `corrupted prior session is quarantined and surfaced as seq-1 recorder_recovered_from_corruption`() = runBlocking {
        val dir = "/prov"
        val valid = chainText(
            "session.start" to mapOf("session_id" to "prev-123"),
            "doc.change" to mapOf("path" to "a.txt"),
            "session.end" to mapOf("reason" to "closed"),
        )
        val lines = valid.trimEnd('\n').split("\n").toMutableList()
        lines[1] = lines[1].replace("a.txt", "TAMPERED.txt")
        val tampered = lines.joinToString("\n") + "\n"
        val deps = FakeRecoveryDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(tampered)))

        val decision = recoverPreviousSession(deps)
        assertTrue(decision is RecoveryDecision.PreviousSessionCorrupt)
        val quarantinedPath = (decision as RecoveryDecision.PreviousSessionCorrupt).quarantinedPath
        assertEquals(listOf("$dir/s.slog" to quarantinedPath), deps.renames)

        val entries = mutableListOf<HashedEnvelope>()
        val h = createSessionHost("new-sess-2", FixedClock()) { entries.add(it) }
        emitNewSessionOpening(h, decision)

        assertEquals("session.start", entries[0].kind)
        assertNull("corruption is never linked via prev_session_id", entries[0].data["prev_session_id"])
        assertEquals("seq 1 is the recovery entry", 1L, entries[1].seq)
        assertEquals("recorder.recovered_from_corruption", entries[1].kind)
        assertEquals(quarantinedPath, entries[1].data["quarantined_path"]?.jsonPrimitive?.content)
    }

    // --- Scenarios 4 & 5: disk-full degraded mode wiring -----------------------------------

    @Test
    fun `write failure on the 50th entry degrades cleanly without retry or further checkpoints`() = runBlocking {
        val writer = FakeWriter(failFromCall = 50)
        val cadence = CheckpointCadence()
        val notifyCount = intArrayOf(0)
        val diskFull = DiskFullHandler(onDegraded = {}, notify = { notifyCount[0]++ })
        val persisted = mutableListOf<Checkpoint>()
        val scheduler = immediateScheduler(persisted)

        val h = host { entry -> routeSessionEntry(entry, writer::append, diskFull, cadence, scheduler::schedule) }

        // Entries 1-49: plain doc.change, all succeed.
        repeat(49) { h.emit("doc.change", buildJsonObject { }) }
        // Entry 50: doc.change again — this is the one whose append() throws.
        h.emit("doc.change", buildJsonObject { })
        // Entries 51-120: a mix of critical and non-critical kinds, to prove the ring keeps
        // only the critical ones and none of the 71 entries reach the writer again.
        repeat(35) {
            h.emit("doc.change", buildJsonObject { })
            h.emit("session.end", buildJsonObject { })
        }
        scheduler.drain()

        // Entries 1-49 (seq 0-48) reached the fake writer; nothing from entry 50 onward did.
        assertEquals(49, writer.appended.size)
        assertEquals((0L until 49L).toList(), writer.appended.map { it.seq })

        // Exactly one notify; handler is degraded from entry 50 onward.
        assertEquals(1, notifyCount[0])
        assertTrue(diskFull.degraded)

        // No checkpoint scheduled for any entry >= 50 — cadence never reached 100 (only 49
        // successful appends), and every entry from 50 onward is routed to the ring instead
        // of the append/cadence branch entirely.
        assertTrue("no checkpoint should have fired (only 49 successful appends, cadence=100)", persisted.isEmpty())

        // Of the 71 entries from #50 onward, only the 35 critical (session.end) ones are in
        // the ring; the 36 non-critical (doc.change, including #50 itself) ones were dropped.
        val ring = diskFull.snapshot()
        assertEquals(35, ring.size)
        assertTrue("all ring entries must be critical", ring.all { it.kind in CRITICAL_KINDS })
        assertTrue("all ring entries are session.end", ring.all { it.kind == "session.end" })
        assertEquals(49, writer.appended.size)
    }

    @Test
    fun `a fake writer that fails once never receives a second append without an explicit clear`() = runBlocking {
        val writer = FakeWriter(failFromCall = 1)
        val cadence = CheckpointCadence()
        val diskFull = DiskFullHandler(onDegraded = {}, notify = {})
        val scheduler = immediateScheduler(mutableListOf())

        val h = host { entry -> routeSessionEntry(entry, writer::append, diskFull, cadence, scheduler::schedule) }
        // First entry fails and degrades; every subsequent entry must be routed to the ring,
        // never back to the (now failed) writer — FakeWriter.append() would throw its own
        // IllegalStateException if it were.
        repeat(10) { h.emit("session.end", buildJsonObject { }) }
        scheduler.drain()

        assertFalse("nothing should have been durably appended", writer.appended.isNotEmpty())
        assertTrue(diskFull.degraded)
    }
}
