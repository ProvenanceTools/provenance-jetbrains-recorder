package dev.provenance.recorder.startup

import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.serializeEntry
import dev.provenance.recorder.session.createSessionHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure logic — JUnit 4. In-memory fake RecoveryDeps; no real filesystem, no IntelliJ. */
class ChainRecoveryTest {
    private val dir = "/prov"

    /** Records every seam call so tests can assert side effects (which file read, rename). */
    private class FakeDeps(
        override val provenanceDir: String,
        private val files: MutableMap<String, SlogReadResult>,
        private val nowInstant: Instant = Instant.parse("2026-07-14T10:20:30.500Z"),
    ) : RecoveryDeps {
        val reads = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()

        override suspend fun readSlogFile(path: String): SlogReadResult {
            reads.add(path)
            return files[path] ?: SlogReadResult.Err("not_found")
        }

        override suspend fun rename(from: String, to: String) {
            renames.add(from to to)
            files[to] = files.remove(from) ?: SlogReadResult.Err("not_found")
        }

        override suspend fun listSlogFiles(dir: String): List<String> =
            files.keys.map { it.substringAfterLast('/') }

        override fun now(): Instant = nowInstant
    }

    /** Build a chain-valid .slog text from (kind, data) pairs via the real session host. */
    private fun chainText(vararg kinds: Pair<String, Map<String, String>>): String {
        val collected = mutableListOf<HashedEnvelope>()
        val host = createSessionHost("sess-xyz", FixedClock()) { collected.add(it) }
        for ((kind, data) in kinds) {
            host.emit(kind, buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
        }
        return collected.joinToString("") { serializeEntry(it) }
    }

    private fun completeSession(sessionId: String = "prev-123"): String =
        chainText(
            "session.start" to mapOf("session_id" to sessionId),
            "doc.change" to mapOf("path" to "a.txt"),
            "session.end" to mapOf("reason" to "closed"),
        )

    private fun danglingSession(sessionId: String = "prev-456"): String =
        chainText(
            "session.start" to mapOf("session_id" to sessionId),
            "doc.change" to mapOf("path" to "a.txt"),
        )

    @Test
    fun `no slog files yields clean start`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf())
        assertEquals(RecoveryDecision.CleanStart, recoverPreviousSession(deps))
    }

    @Test
    fun `alphabetically last slog is chosen`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(completeSession("first")),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(completeSession("last")),
            ),
        )
        val decision = recoverPreviousSession(deps)
        assertEquals(RecoveryDecision.PreviousSessionComplete("last"), decision)
        assertEquals(listOf("$dir/session-zzz.slog"), deps.reads)
    }

    @Test
    fun `complete session yields PreviousSessionComplete with no quarantine`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(completeSession("prev-123"))))
        assertEquals(RecoveryDecision.PreviousSessionComplete("prev-123"), recoverPreviousSession(deps))
        assertTrue("nothing quarantined", deps.renames.isEmpty())
    }

    @Test
    fun `dangling session yields PreviousSessionDangling with path and no quarantine`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(danglingSession("prev-456"))))
        val decision = recoverPreviousSession(deps)
        assertEquals(RecoveryDecision.PreviousSessionDangling("prev-456", "$dir/s.slog"), decision)
        assertTrue("nothing quarantined", deps.renames.isEmpty())
    }

    @Test
    fun `unreadable file is quarantined with the exact colon-and-dot-replaced path`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Err("read_error")))
        val decision = recoverPreviousSession(deps)
        val expectedQuarantine = "$dir/s.slog.corrupt-2026-07-14T10-20-30-500Z"
        assertEquals(RecoveryDecision.PreviousSessionCorrupt(expectedQuarantine), decision)
        assertEquals(listOf("$dir/s.slog" to expectedQuarantine), deps.renames)
    }

    @Test
    fun `broken hash chain is quarantined`() = runBlocking {
        // Build a valid chain, then tamper the second line's data so its stored hash no
        // longer matches the recomputed one (mirrors ChainValidator's tamper test).
        val valid = completeSession("prev-123")
        val lines = valid.trimEnd('\n').split("\n").toMutableList()
        lines[1] = lines[1].replace("a.txt", "TAMPERED.txt")
        val tampered = lines.joinToString("\n") + "\n"
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(tampered)))
        val decision = recoverPreviousSession(deps)
        assertTrue(decision is RecoveryDecision.PreviousSessionCorrupt)
        assertEquals(1, deps.renames.size)
    }

    @Test
    fun `unparsable text is quarantined`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok("{ not valid json\n")))
        assertTrue(recoverPreviousSession(deps) is RecoveryDecision.PreviousSessionCorrupt)
    }

    @Test
    fun `first entry not session_start is quarantined`() = runBlocking {
        val text = chainText("doc.change" to mapOf("path" to "a.txt"))
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(text)))
        assertTrue(recoverPreviousSession(deps) is RecoveryDecision.PreviousSessionCorrupt)
    }

    @Test
    fun `session_start without session_id is quarantined`() = runBlocking {
        val text = chainText("session.start" to mapOf("format_version" to "1.0"))
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(text)))
        assertTrue(recoverPreviousSession(deps) is RecoveryDecision.PreviousSessionCorrupt)
    }
}
