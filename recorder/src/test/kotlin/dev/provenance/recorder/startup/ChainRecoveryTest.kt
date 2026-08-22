package dev.provenance.recorder.startup

import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.serializeEntry
import dev.provenance.recorder.session.createSessionHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
        override val ownStudentRef: String? = null,
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

    /**
     * Build a chain-valid .slog text from (kind, data) pairs via the real session host.
     *
     * [startWall] seeds the FixedClock, so the emitted `session.start.wall` is the value
     * ownership-aware selection orders on. `data` is a JsonObject rather than a flat
     * Map<String, String> because `student_ref` lives at
     * `data.identity.enrollment.student_ref` — nested, not flat.
     */
    private fun chainText(
        vararg kinds: Pair<String, JsonObject>,
        startWall: Instant = Instant.parse("2026-07-14T09:00:00.000Z"),
    ): String {
        val collected = mutableListOf<HashedEnvelope>()
        val host = createSessionHost("sess-xyz", FixedClock(initialWall = startWall)) { collected.add(it) }
        for ((kind, data) in kinds) {
            host.emit(kind, data)
        }
        return collected.joinToString("") { serializeEntry(it) }
    }

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /** A `session.start` payload, optionally carrying an enrollment identity. */
    private fun startData(sessionId: String, studentRef: String? = null): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        if (studentRef != null) {
            putJsonObject("identity") {
                putJsonObject("enrollment") { put("student_ref", studentRef) }
            }
        }
    }

    private fun completeSession(
        sessionId: String = "prev-123",
        studentRef: String? = null,
        startWall: Instant = Instant.parse("2026-07-14T09:00:00.000Z"),
    ): String =
        chainText(
            "session.start" to startData(sessionId, studentRef),
            "doc.change" to obj("path" to "a.txt"),
            "session.end" to obj("reason" to "closed"),
            startWall = startWall,
        )

    private fun danglingSession(
        sessionId: String = "prev-456",
        studentRef: String? = null,
        startWall: Instant = Instant.parse("2026-07-14T09:00:00.000Z"),
    ): String =
        chainText(
            "session.start" to startData(sessionId, studentRef),
            "doc.change" to obj("path" to "a.txt"),
            startWall = startWall,
        )

    @Test
    fun `no slog files yields clean start`() = runBlocking {
        val deps = FakeDeps(dir, mutableMapOf())
        assertEquals(RecoveryDecision.CleanStart, recoverPreviousSession(deps))
    }

    // -----------------------------------------------------------------------
    // Selection: latest session.start wall, NOT the alphabetically last filename.
    // -----------------------------------------------------------------------

    @Test
    fun `latest session start wall wins over the alphabetically last filename`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(
                    completeSession("newer", "alice", Instant.parse("2026-07-14T11:00:00.000Z")),
                ),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(
                    completeSession("older", "alice", Instant.parse("2026-07-14T09:00:00.000Z")),
                ),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("newer"), recoverPreviousSession(deps))
        assertTrue("nothing quarantined", deps.renames.isEmpty())
    }

    @Test
    fun `equal walls tie-break on filename descending`() = runBlocking {
        val sameWall = Instant.parse("2026-07-14T10:00:00.000Z")
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(completeSession("first", "alice", sameWall)),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(completeSession("last", "alice", sameWall)),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("last"), recoverPreviousSession(deps))
    }

    @Test
    fun `a partner's newer slog never wins over our own older one`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(
                    completeSession("ours", "alice", Instant.parse("2026-07-14T09:00:00.000Z")),
                ),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(
                    completeSession("theirs", "bob", Instant.parse("2026-07-14T23:00:00.000Z")),
                ),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("ours"), recoverPreviousSession(deps))
        assertTrue("nothing quarantined", deps.renames.isEmpty())
    }

    // -----------------------------------------------------------------------
    // A FOREIGN .slog IS NEVER RENAMED. This is the regression guard for the
    // evidence-destruction bug: in a shared, committed .provenance/ the file
    // that fails to read/parse/validate is very often the PARTNER'S, and
    // quarantining it removes their evidence from the submission entirely.
    // -----------------------------------------------------------------------

    @Test
    fun `an UNREADABLE foreign slog is never renamed`() = runBlocking {
        // An unreadable file cannot name its owner, so it classifies as `unattributed`
        // — and an ENROLLED recorder (non-null ownStudentRef) may not touch a file it
        // cannot prove is its own. It is not selected and, crucially, not quarantined.
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(
                    completeSession("ours", "alice", Instant.parse("2026-07-14T09:00:00.000Z")),
                ),
                "$dir/session-zzz.slog" to SlogReadResult.Err("read_error"),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("ours"), recoverPreviousSession(deps))
        assertTrue("foreign .slog was renamed", deps.renames.isEmpty())
    }

    @Test
    fun `a chain-broken foreign slog is never renamed`() = runBlocking {
        val partner = completeSession("theirs", "bob", Instant.parse("2026-07-14T23:00:00.000Z"))
        val lines = partner.trimEnd('\n').split("\n").toMutableList()
        lines[1] = lines[1].replace("a.txt", "TAMPERED.txt")
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(
                    completeSession("ours", "alice", Instant.parse("2026-07-14T09:00:00.000Z")),
                ),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(lines.joinToString("\n") + "\n"),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("ours"), recoverPreviousSession(deps))
        assertTrue("foreign .slog was renamed", deps.renames.isEmpty())
    }

    @Test
    fun `enrolled recorder in an all-foreign directory yields CleanStart with zero renames`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-bob1.slog" to SlogReadResult.Ok(danglingSession("theirs-1", "bob")),
                "$dir/session-bob2.slog" to SlogReadResult.Err("read_error"),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.CleanStart, recoverPreviousSession(deps))
        assertTrue("a partner's .slog was renamed", deps.renames.isEmpty())
    }

    @Test
    fun `an UNENROLLED recorder never touches a slog that names a student`() = runBlocking {
        // Asymmetric `foreign`: we hold no identity and the candidate holds one. We cannot
        // claim to be a contributor we cannot name, so the file is theirs, not ours.
        // The first line must stay INTACT — that is what names bob. The corruption is
        // further down (a half-written second entry), which is exactly the shape a partner's
        // log has mid-`git checkout`.
        val partner = danglingSession("theirs", "bob")
        val lines = partner.trimEnd('\n').split("\n")
        val truncated = lines[0] + "\n" + lines[1].substring(0, lines[1].length / 2) + "\n"
        val deps = FakeDeps(
            dir,
            mutableMapOf("$dir/session-bob.slog" to SlogReadResult.Ok(truncated)),
            ownStudentRef = null,
        )
        assertEquals(RecoveryDecision.CleanStart, recoverPreviousSession(deps))
        assertTrue("a partner's .slog was renamed", deps.renames.isEmpty())
    }

    // -----------------------------------------------------------------------
    // A DAMAGED WALL CLOCK COSTS A .slog ITS ORDER, NEVER ITS AUTHOR.
    // -----------------------------------------------------------------------

    /**
     * Damage ONLY the `wall` on the first line, leaving `student_ref` intact and the
     * line valid JSON. The hash covers `wall`, so the chain no longer validates — which
     * is precisely what used to drive the quarantine.
     *
     * Damaging the wall rather than truncating is the point: a truncated first line
     * would erase `student_ref` too, and a fixture that does that tests nothing here.
     */
    private fun damageWall(text: String): String =
        text.replaceFirst(Regex("\"wall\":\"[^\"]*\""), "\"wall\":\"2026-13-45T99:99:99.999Z\"")

    @Test
    fun `does not quarantine a partner's log whose only damage is its wall clock`() = runBlocking {
        // The narrowest reachable form of the evidence-destruction bug, and the one an
        // attacker gets for free: `session.start.wall` is a plain string in the clear.
        // The whole first-line parse used to fail on it, throwing away `student_ref`
        // with the timestamp and demoting bob's log to `unattributed`. An UNENROLLED
        // recorder may act on `unattributed` files, so alice — who has done nothing but
        // not enroll — selected bob's log, failed its now-broken chain, and renamed it
        // `.corrupt-<ISO>`. Sealing excludes `.corrupt-` files, so bob's evidence left
        // the submission with alice's commit as the paper trail.
        val deps = FakeDeps(
            dir,
            mutableMapOf("$dir/session-bob.slog" to SlogReadResult.Ok(damageWall(danglingSession("theirs", "bob")))),
            ownStudentRef = null,
        )
        assertEquals(RecoveryDecision.CleanStart, recoverPreviousSession(deps))
        assertTrue("a partner's .slog was renamed", deps.renames.isEmpty())
    }

    @Test
    fun `an enrolled recorder is not blocked from quarantining its OWN wall-damaged log`() = runBlocking {
        // The other half of the rule. The all-or-nothing parse demoted our own damaged
        // log to `unattributed` too, which an ENROLLED recorder may not touch — so it
        // silently lost the ability to recover from its own corruption.
        val deps = FakeDeps(
            dir,
            mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(damageWall(completeSession("mine", "alice")))),
            ownStudentRef = "alice",
        )
        val decision = recoverPreviousSession(deps)
        assertEquals(
            RecoveryDecision.PreviousSessionCorrupt("$dir/s.slog.corrupt-2026-07-14T10-20-30-500Z"),
            decision,
        )
        assertEquals(1, deps.renames.size)
    }

    @Test
    fun `an enrolled recorder never touches a partner's wall-damaged log`() = runBlocking {
        // Defence in depth alongside the unenrolled case above. This one held before the
        // fix too (an enrolled recorder may not touch `unattributed` files either), but
        // it is the assertion that must not regress if eligibility is ever loosened.
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/session-aaa.slog" to SlogReadResult.Ok(
                    completeSession("ours", "alice", Instant.parse("2026-07-14T09:00:00.000Z")),
                ),
                "$dir/session-zzz.slog" to SlogReadResult.Ok(damageWall(danglingSession("theirs", "bob"))),
            ),
            ownStudentRef = "alice",
        )
        assertEquals(RecoveryDecision.PreviousSessionComplete("ours"), recoverPreviousSession(deps))
        assertTrue("a partner's .slog was renamed", deps.renames.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Unenrolled recorder + unattributed files: the pre-enrollment solo
    // behaviour, unchanged.
    // -----------------------------------------------------------------------

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
    fun `an enrolled recorder still recovers its OWN dangling session`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(danglingSession("prev-456", "alice"))),
            ownStudentRef = "alice",
        )
        val decision = recoverPreviousSession(deps)
        assertEquals(RecoveryDecision.PreviousSessionDangling("prev-456", "$dir/s.slog"), decision)
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
    fun `with nothing parseable the fallback is the alphabetically last ELIGIBLE file`() = runBlocking {
        val deps = FakeDeps(
            dir,
            mutableMapOf(
                "$dir/s-a.slog" to SlogReadResult.Err("read_error"),
                "$dir/s-z.slog" to SlogReadResult.Err("read_error"),
            ),
        )
        val decision = recoverPreviousSession(deps)
        assertEquals(
            RecoveryDecision.PreviousSessionCorrupt("$dir/s-z.slog.corrupt-2026-07-14T10-20-30-500Z"),
            decision,
        )
        assertEquals(1, deps.renames.size)
        assertEquals("$dir/s-z.slog", deps.renames[0].first)
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
        val text = chainText("doc.change" to obj("path" to "a.txt"))
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(text)))
        assertTrue(recoverPreviousSession(deps) is RecoveryDecision.PreviousSessionCorrupt)
    }

    @Test
    fun `session_start without session_id is quarantined`() = runBlocking {
        val text = chainText("session.start" to obj("format_version" to "1.0"))
        val deps = FakeDeps(dir, mutableMapOf("$dir/s.slog" to SlogReadResult.Ok(text)))
        assertTrue(recoverPreviousSession(deps) is RecoveryDecision.PreviousSessionCorrupt)
    }
}
