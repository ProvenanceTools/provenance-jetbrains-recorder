package dev.provenance.recorder.wiring

import dev.provenance.core.ChainCheck
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.PeerObservedParse
import dev.provenance.core.PeerObservedPayload
import dev.provenance.core.PeerObservedState
import dev.provenance.core.Sha256
import dev.provenance.core.serializeEntry
import dev.provenance.core.toJsonObject
import dev.provenance.core.validateChain
import dev.provenance.core.validatePeerObservedPayload
import dev.provenance.recorder.session.createSessionHost
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PEER WITNESSING, writer half (program spec §7 mechanism 2, collaboration spec §5.5).
 *
 * Every one of the SEVEN CORRECTIONS the VS Code implementation surfaced has a test here,
 * named for it. Three recorders describing one event three different ways is precisely the
 * divergence the shared vectors exist to prevent, so a correction with no test is a correction
 * that will be undone.
 */
class PeerWatcherTest {

    private val partner = "session-11111111-1111-4111-8111-111111111111.slog"
    private val mine = "session-99999999-9999-4999-8999-999999999999.slog"

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** A scripted [PeerFiles]. Records every call, so "never touched" is assertable. */
    private class FakeFiles : PeerFiles {
        val contents = LinkedHashMap<String, ByteArray>()
        val unreadable = HashSet<String>()
        var listFails = false
        val reads = mutableListOf<String>()
        var lists = 0

        override fun list(): List<String> {
            lists++
            if (listFails) return emptyList()
            return contents.keys.toList()
        }

        override fun read(name: String): ForeignLogRead {
            reads.add(name)
            if (name in unreadable) return ForeignLogRead.Failed(ForeignReadFailure.UNREADABLE)
            val bytes = contents[name] ?: return ForeignLogRead.Failed(ForeignReadFailure.GONE)
            return ForeignLogRead.Bytes(bytes)
        }
    }

    private class Collected {
        val payloads = mutableListOf<PeerObservedPayload>()
        val errors = mutableListOf<Throwable>()
    }

    private fun watcherOver(files: FakeFiles, out: Collected): PeerWatcher = PeerWatcher(
        files = files,
        isOwnFile = { it == mine || it == "$mine.meta" },
        emit = { out.payloads.add(it) },
        onError = { out.errors.add(it) },
    )

    /**
     * A REAL recorder-produced `.slog`: a real [dev.provenance.core.SessionHost], really
     * chained, really serialized. Hand-built NDJSON would let the tip reader pass on a shape
     * production never produces.
     */
    private fun realLog(sessionId: String, extraEntries: Int): ByteArray {
        val out = mutableListOf<HashedEnvelope>()
        val host = createSessionHost(sessionId, FixedClock(0)) { out.add(it) }
        host.emit("session.start", buildJsonObject { put("session_id", sessionId) })
        repeat(extraEntries) { i ->
            host.emit("doc.change", buildJsonObject { put("i", i) })
        }
        return out.joinToString("") { serializeEntry(it) }.toByteArray(Charsets.UTF_8)
    }

    // -----------------------------------------------------------------------
    // The ordinary witness
    // -----------------------------------------------------------------------

    @Test
    fun `a partner's log that appears is witnessed with its chain tip`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        val bytes = realLog("partner-session", extraEntries = 3)
        files.contents[partner] = bytes

        watcher.enqueue(partner)
        watcher.drain()

        assertEquals(1, out.payloads.size)
        val p = out.payloads[0]
        assertEquals(partner, p.file)
        assertEquals(Sha256.hex(bytes), p.sha256)
        assertEquals(bytes.size.toLong(), p.bytes)
        assertEquals("partner-session", p.sessionId)
        // session.start plus three doc.change: seqs 0..3.
        assertEquals(3L, p.seqHigh)
        assertNotNull(p.lastHash)
        assertEquals(PeerObservedState.APPEARED, p.state)
        // ...and the payload this port emits is one the shared reader accepts.
        assertTrue(validatePeerObservedPayload(p.toJsonObject()) is PeerObservedParse.Ok)
    }

    /** The foreign chain is READ, not guessed: the tip comes off a real serialized log. */
    @Test
    fun `the chain tip is read out of a real recorder-produced log`() {
        val bytes = realLog("partner-session", extraEntries = 7)
        val tip = readForeignChainTip(bytes.toString(Charsets.UTF_8))
        assertEquals("partner-session", tip.sessionId)
        assertEquals(7L, tip.seqHigh)
        assertEquals(64, tip.lastHash!!.length)
    }

    // -----------------------------------------------------------------------
    // CORRECTION 1 — a same-length rewrite is `grew`, with `bytes` alongside
    // -----------------------------------------------------------------------

    /**
     * The five states do not partition reality. VS Code reports a same-length rewrite as
     * `grew` and emits `bytes` beside it so a reader can see the length did not change;
     * `shrank` is described in the vectors as "catches a truncation", and reaching for it here
     * would lean a DESCRIPTIVE field toward accusation. This port makes the identical choice.
     */
    @Test
    fun `correction 1 - a same-length rewrite is grew, never shrank`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)

        val first = realLog("partner-session", extraEntries = 2)
        files.contents[partner] = first
        watcher.enqueue(partner)
        watcher.drain()

        // Same LENGTH, different BYTES. Not append-only growth, and not a truncation.
        val rewritten = first.copyOf()
        rewritten[rewritten.size / 2] = if (rewritten[rewritten.size / 2] == 'x'.code.toByte()) {
            'y'.code.toByte()
        } else {
            'x'.code.toByte()
        }
        assertEquals(first.size, rewritten.size)
        assertFalse(first.contentEquals(rewritten))
        files.contents[partner] = rewritten
        watcher.enqueue(partner)
        watcher.drain()

        assertEquals(2, out.payloads.size)
        val second = out.payloads[1]
        assertEquals(PeerObservedState.GREW, second.state)
        // `bytes` is what lets a reader see the length did not change.
        assertEquals(out.payloads[0].bytes, second.bytes)
    }

    @Test
    fun `a longer log is grew and a shorter one is shrank`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)

        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        watcher.drain()
        files.contents[partner] = realLog("partner-session", extraEntries = 20)
        watcher.drain()
        files.contents[partner] = realLog("partner-session", extraEntries = 1)
        watcher.drain()

        assertEquals(
            listOf(PeerObservedState.APPEARED, PeerObservedState.GREW, PeerObservedState.SHRANK),
            out.payloads.map { it.state },
        )
    }

    // -----------------------------------------------------------------------
    // CORRECTION 2 — an unchanged file is NOT re-emitted
    // -----------------------------------------------------------------------

    /**
     * Emitting unconditionally re-witnesses every partner log at every checkpoint, forever —
     * an unbounded stream of identical entries in a signed chain a human eventually reads.
     */
    @Test
    fun `correction 2 - an unchanged file is never re-emitted, however many drains run`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 4)

        repeat(50) {
            watcher.enqueue(partner)
            watcher.drain()
        }

        assertEquals(1, out.payloads.size)
        assertEquals(PeerObservedState.APPEARED, out.payloads[0].state)
        // It really was re-READ each round — the skip is a content decision, not a
        // never-looked-again one, which is what makes the `grew` case above reachable.
        assertTrue("the file must still be re-read each drain", files.reads.count { it == partner } >= 50)
    }

    // -----------------------------------------------------------------------
    // CORRECTION 3 — `disappeared` requires a prior observation
    // -----------------------------------------------------------------------

    /**
     * "Carries the last state seen" is unreachable if you never saw it, and a delete for a
     * never-observed file has no honest digest. Inventing one would be manufacturing evidence
     * about a third party's artifact.
     */
    @Test
    fun `correction 3 - a delete for a never-observed file emits nothing`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)

        // The name is known (a VFS delete event named it) but it was never read.
        watcher.enqueue(partner)
        watcher.drain()

        assertEquals(emptyList<PeerObservedPayload>(), out.payloads)
    }

    @Test
    fun `disappeared carries the LAST state seen, and is emitted exactly once`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        val bytes = realLog("partner-session", extraEntries = 5)
        files.contents[partner] = bytes
        watcher.drain()

        files.contents.remove(partner)
        repeat(10) { watcher.drain() }

        assertEquals(2, out.payloads.size)
        val gone = out.payloads[1]
        assertEquals(PeerObservedState.DISAPPEARED, gone.state)
        // The digest and chain fields describe the last state seen — that is what makes the
        // observation evidentiary rather than a bare "it is not here".
        assertEquals(Sha256.hex(bytes), gone.sha256)
        assertEquals(bytes.size.toLong(), gone.bytes)
        assertEquals("partner-session", gone.sessionId)
        assertEquals(5L, gone.seqHigh)
        assertEquals(out.payloads[0].lastHash, gone.lastHash)
    }

    /** A file that comes back is `appeared` again, not `grew` off a stale baseline. */
    @Test
    fun `a file that returns after disappearing is appeared again`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        watcher.drain()
        files.contents.remove(partner)
        watcher.drain()
        files.contents[partner] = realLog("partner-session", extraEntries = 9)
        watcher.drain()

        assertEquals(
            listOf(
                PeerObservedState.APPEARED,
                PeerObservedState.DISAPPEARED,
                PeerObservedState.APPEARED,
            ),
            out.payloads.map { it.state },
        )
    }

    // -----------------------------------------------------------------------
    // CORRECTION 4 — a local read failure is not an absence
    // -----------------------------------------------------------------------

    /**
     * `EACCES` / `EIO` / a Windows sharing violation is a fact about THIS machine, not about
     * the partner's file. Turning it into `disappeared` would put a claim about somebody
     * else's artifact into a signed chain on the strength of a local failure.
     */
    @Test
    fun `correction 4 - an unreadable file emits nothing at all`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        watcher.drain()
        assertEquals(1, out.payloads.size)

        // Still there, still listed — just not readable by us right now.
        files.unreadable.add(partner)
        repeat(5) { watcher.drain() }
        assertEquals("an unreadable file must produce no observation", 1, out.payloads.size)

        // ...and it did NOT quietly mark the file absent: when the read succeeds again the
        // content is unchanged, so there is still nothing new to say.
        files.unreadable.remove(partner)
        watcher.drain()
        assertEquals(1, out.payloads.size)
    }

    @Test
    fun `an unreadable file that was never seen also emits nothing`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 1)
        files.unreadable.add(partner)
        watcher.drain()
        assertEquals(emptyList<PeerObservedPayload>(), out.payloads)
    }

    // -----------------------------------------------------------------------
    // CORRECTION 5 — `unparseable` REQUIRES all three chain fields null
    // -----------------------------------------------------------------------

    /**
     * Every unreadable chain is routed to `unparseable`. A port emitting `grew` with all-nulls
     * passes the reader's narrowing while violating its intent: the state and the nulls must
     * AGREE, not merely each be individually legal.
     */
    @Test
    fun `correction 5 - every unreadable chain is unparseable with all three nulls`() {
        val unreadableChains = mapOf(
            "not ndjson at all" to "<<<<<<< HEAD\nnot json\n".toByteArray(),
            "truncated mid-write" to
                realLog("partner-session", 4).let { it.copyOf(it.size - 10) },
            "empty file" to ByteArray(0),
            "valid json, no session.start" to
                "{\"seq\":0,\"t\":0,\"wall\":\"x\",\"kind\":\"doc.change\",\"data\":{},\"prev_hash\":\"a\",\"hash\":\"b\"}\n"
                    .toByteArray(),
        )
        for ((label, bytes) in unreadableChains) {
            val files = FakeFiles()
            val out = Collected()
            val watcher = watcherOver(files, out)
            files.contents[partner] = bytes
            watcher.drain()

            assertEquals(label, 1, out.payloads.size)
            val p = out.payloads[0]
            assertEquals(label, PeerObservedState.UNPARSEABLE, p.state)
            assertNull(label, p.sessionId)
            assertNull(label, p.seqHigh)
            assertNull(label, p.lastHash)
            // The digest and length still describe what was there — the file is not ignored,
            // it is RECORDED as unreadable.
            assertEquals(label, Sha256.hex(bytes), p.sha256)
            assertEquals(label, bytes.size.toLong(), p.bytes)
            // And the shared reader accepts exactly this combination and no other.
            assertTrue(label, validatePeerObservedPayload(p.toJsonObject()) is PeerObservedParse.Ok)
        }
    }

    /**
     * A log that becomes readable later is witnessed properly, and a log that becomes
     * unreadable is re-witnessed as `unparseable` — the state is per-observation, never sticky.
     */
    @Test
    fun `unparseable is a per-observation state, not a sticky one`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = "garbage\n".toByteArray()
        watcher.drain()
        files.contents[partner] = realLog("partner-session", extraEntries = 3)
        watcher.drain()

        assertEquals(
            listOf(PeerObservedState.UNPARSEABLE, PeerObservedState.GREW),
            out.payloads.map { it.state },
        )
        assertEquals("partner-session", out.payloads[1].sessionId)
    }

    // -----------------------------------------------------------------------
    // CORRECTION 6 — checkpoint + teardown, no timer
    // -----------------------------------------------------------------------

    /**
     * The contract's "checkpoint or a timer, whichever is later" reads backwards — running
     * both gives whichever is SOONER. This port wires the checkpoint cadence and the teardown
     * drain and nothing else, so witnessing is at worst LATE, never lost. The watcher itself
     * therefore owns no timer, no scheduler and no thread: it acts only when drained.
     */
    @Test
    fun `correction 6 - the watcher owns no timer and acts only when drained`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 3)
        watcher.enqueue(partner)

        // Enqueueing does nothing on its own — no read, no list, no emit.
        assertEquals(emptyList<String>(), files.reads)
        assertEquals(0, files.lists)
        assertEquals(emptyList<PeerObservedPayload>(), out.payloads)

        Thread.sleep(50)
        assertEquals("nothing may happen on a timer", 0, files.lists)

        watcher.drain()
        assertEquals(1, out.payloads.size)
    }

    // -----------------------------------------------------------------------
    // CORRECTION 7 / rule 7 — `seq_high: 0` is legal and is not absence
    // -----------------------------------------------------------------------

    /**
     * A foreign log holding only its `session.start` has `seqHigh == 0`. A truthiness check
     * anywhere in the emitter turns the shortest possible honest witness into a malformed one
     * — and `unparseable` with a named session is the one shape the reader rejects outright.
     */
    @Test
    fun `seq_high zero is a real value, not absence`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 0)
        watcher.drain()

        val p = out.payloads.single()
        assertEquals(0L, p.seqHigh)
        assertEquals("partner-session", p.sessionId)
        assertEquals(PeerObservedState.APPEARED, p.state)
        assertNotNull(p.lastHash)
        val json = p.toJsonObject()
        assertEquals(0L, json["seq_high"]!!.jsonPrimitive.content.toLong())
        assertTrue(validatePeerObservedPayload(json) is PeerObservedParse.Ok)
    }

    // -----------------------------------------------------------------------
    // Rule 6 — nulls are emitted explicitly
    // -----------------------------------------------------------------------

    /**
     * An omitted key and a `null` value canonicalize differently and therefore chain to
     * different hashes, so a port that omits them produces a log whose entries hash
     * differently from every other recorder's for the identical observation.
     */
    @Test
    fun `the three chain fields are always PRESENT keys, null or not`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = "garbage\n".toByteArray()
        watcher.drain()
        files.contents[partner] = realLog("partner-session", extraEntries = 1)
        watcher.drain()

        assertEquals(2, out.payloads.size)
        for (p in out.payloads) {
            val json = p.toJsonObject()
            assertEquals(
                setOf("file", "sha256", "bytes", "session_id", "seq_high", "last_hash", "state"),
                json.keys,
            )
        }
        val unparseable = out.payloads[0].toJsonObject()
        assertEquals(JsonNull, unparseable["session_id"])
        assertEquals(JsonNull, unparseable["seq_high"])
        assertEquals(JsonNull, unparseable["last_hash"])
    }

    // -----------------------------------------------------------------------
    // Rule 4 / scope — own files, and only `.slog`
    // -----------------------------------------------------------------------

    /** A chain cannot corroborate itself, and the writer must not make the reader clean up. */
    @Test
    fun `this session's own log and meta are never witnessed`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[mine] = realLog("my-session", extraEntries = 4)
        files.contents["$mine.meta"] = "{}".toByteArray()
        files.contents[partner] = realLog("partner-session", extraEntries = 4)

        watcher.enqueue(mine)
        watcher.enqueue("$mine.meta")
        watcher.drain()

        assertEquals(listOf(partner), out.payloads.map { it.file })
        assertFalse("our own log must never even be read", files.reads.contains(mine))
    }

    /**
     * Only `*.slog`. `.slog.meta` carries the session key and the checkpoints, not the chain a
     * witness commits to; the rolling manifests are the seal, which the loader already
     * reconciles; and a quarantined `.corrupt-<ISO>` is not a chain at all.
     */
    @Test
    fun `only slog files are witnessed`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 1)
        files.contents["$partner.meta"] = "{}".toByteArray()
        files.contents["manifest-11111111-1111-4111-8111-111111111111.json"] = "{}".toByteArray()
        files.contents["manifest-11111111-1111-4111-8111-111111111111.sig"] = "ff".toByteArray()
        files.contents["session-abc.slog.corrupt-2026-08-20T00:00:00Z"] = "x".toByteArray()
        files.contents["session-abc.slog.tmp"] = "x".toByteArray()

        watcher.drain()

        assertEquals(listOf(partner), out.payloads.map { it.file })
        assertTrue(isWitnessableLogName(partner))
        assertFalse(isWitnessableLogName("$partner.meta"))
        assertFalse(isWitnessableLogName("manifest-x.json"))
        assertFalse(isWitnessableLogName("session-abc.slog.corrupt-2026-08-20T00:00:00Z"))
    }

    /** The payload names a FILE and a CHAIN POSITION. No path, no identity, ever. */
    @Test
    fun `the payload carries a basename and no identity`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        watcher.drain()

        val json = out.payloads.single().toJsonObject()
        val file = json["file"]!!.jsonPrimitive.content
        assertFalse(file.contains('/'))
        assertFalse(file.contains('\\'))
        assertEquals(
            setOf("file", "sha256", "bytes", "session_id", "seq_high", "last_hash", "state"),
            json.keys,
        )
    }

    // -----------------------------------------------------------------------
    // Rule 5 — a foreign file is NEVER touched
    // -----------------------------------------------------------------------

    /**
     * Decision-log bug 2 was a startup recovery that RENAMED a partner's log it could not
     * validate. In a shared repo that destroys the victim's evidence and makes git blame them
     * for it, and it hands an attacker a way to delete a partner's log by corrupting one byte.
     *
     * Asserted against a REAL directory, over the whole hostile set — unparseable, truncated,
     * conflict-marked, zero-byte — and over many drains: every byte, every name, and every
     * modification time is unchanged, and nothing was created or removed.
     */
    @Test
    fun `a foreign file is never renamed, rewritten, truncated or deleted`() {
        val dir: Path = Files.createTempDirectory("provjet-peer-untouched")
        try {
            val hostile = mapOf(
                "session-aaaa1111-1111-4111-8111-111111111111.slog" to "<<<<<<< HEAD\ngarbage\n",
                "session-bbbb2222-2222-4222-8222-222222222222.slog" to "",
                "session-cccc3333-3333-4333-8333-333333333333.slog" to "{\"partial\":",
            )
            for ((name, text) in hostile) Files.writeString(dir.resolve(name), text)
            Files.writeString(dir.resolve("session-dddd.slog.meta"), "{}")
            val realPartner = "session-eeee4444-4444-4444-8444-444444444444.slog"
            Files.write(dir.resolve(realPartner), realLog("partner-session", 3))

            val before = Files.list(dir).use { s ->
                s.toList().associate {
                    it.fileName.toString() to
                        Pair(Files.readAllBytes(it).toList(), Files.getLastModifiedTime(it))
                }
            }

            val out = Collected()
            val watcher = PeerWatcher(
                files = NioPeerFiles(dir),
                isOwnFile = { false },
                emit = { out.payloads.add(it) },
                onError = { out.errors.add(it) },
            )
            repeat(5) { watcher.drain() }

            val after = Files.list(dir).use { s ->
                s.toList().associate {
                    it.fileName.toString() to
                        Pair(Files.readAllBytes(it).toList(), Files.getLastModifiedTime(it))
                }
            }
            assertEquals("no file may be created, renamed or removed", before.keys, after.keys)
            assertEquals("no byte and no mtime may change", before, after)

            // And the hostile ones were still WITNESSED — `unparseable` is the entire response.
            val unparseable = out.payloads.filter { it.state == PeerObservedState.UNPARSEABLE }
            assertEquals(hostile.keys, unparseable.map { it.file }.toSet())
            assertEquals(emptyList<Throwable>(), out.errors)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // The directory sweep (provjet's cached-VFS deviation)
    // -----------------------------------------------------------------------

    /**
     * IntelliJ's VFS is a CACHED layer that refreshes on window focus, so a `git pull` in an
     * external terminal produces no VFS event at all until something triggers a refresh. A
     * watcher-only port would silently witness nothing for the single most common way a
     * partner's log arrives, so the drain sweeps the directory itself.
     */
    @Test
    fun `a file that arrives with no VFS event at all is still witnessed`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)

        // Nothing was ever enqueued: this is the cold-VFS case.
        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        watcher.drain()

        assertEquals(listOf(partner), out.payloads.map { it.file })
    }

    /** A directory that cannot be listed costs witnessing, never recording. */
    @Test
    fun `a failing directory listing degrades to no observation`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", extraEntries = 2)
        files.listFails = true
        watcher.drain()
        assertEquals(emptyList<PeerObservedPayload>(), out.payloads)
    }

    /** Best effort PER FILE: one hostile log must not cost the others their witness. */
    @Test
    fun `a throwing read costs only that one file`() {
        val other = "session-22222222-2222-4222-8222-222222222222.slog"
        val out = Collected()
        val exploding = object : PeerFiles {
            override fun list(): List<String> = listOf(partner, other)
            override fun read(name: String): ForeignLogRead =
                if (name == partner) throw IllegalStateException("boom")
                else ForeignLogRead.Bytes(realLog("other-session", 1))
        }
        val watcher = PeerWatcher(
            files = exploding,
            isOwnFile = { false },
            emit = { out.payloads.add(it) },
            onError = { out.errors.add(it) },
        )
        watcher.drain()

        assertEquals(listOf(other), out.payloads.map { it.file })
        assertEquals(1, out.errors.size)
    }

    /** Deterministic emission order, so two runs over one directory produce one log. */
    @Test
    fun `several files in one drain are emitted in sorted order`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        val names = listOf("session-c.slog", "session-a.slog", "session-b.slog")
        for (n in names) files.contents[n] = realLog("s-$n", 1)
        watcher.drain()
        assertEquals(names.sorted(), out.payloads.map { it.file })
    }

    @Test
    fun `a disposed watcher accepts nothing and emits nothing`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)
        files.contents[partner] = realLog("partner-session", 2)
        watcher.dispose()
        watcher.enqueue(partner)
        watcher.drain()
        assertEquals(emptyList<PeerObservedPayload>(), out.payloads)
    }

    // -----------------------------------------------------------------------
    // The chain: suppressed events consume no seq, and the SessionHost seam holds
    // -----------------------------------------------------------------------

    /**
     * A SUPPRESSED observation must consume no `seq`. Dropping an event AFTER `emit` would
     * chain it and then discard it, leaving a hole that validation check 4 (`seq_gaps`) reads
     * as a DELETED ENTRY — a tamper finding manufactured against a student for nothing but
     * having an unchanged partner log in their tree.
     *
     * The gate is BEFORE `emit` by construction: [PeerWatcher.observe] returns null and
     * nothing reaches the session host at all.
     */
    @Test
    fun `a suppressed observation consumes no seq and leaves no hole`() {
        val entries = Collections.synchronizedList(mutableListOf<HashedEnvelope>())
        val host = createSessionHost("mine", FixedClock(0)) { entries.add(it) }
        host.emit("session.start", buildJsonObject { put("session_id", "mine") })

        val files = FakeFiles()
        val watcher = PeerWatcher(
            files = files,
            isOwnFile = { it == mine },
            emit = { host.emit("peer.observed", it.toJsonObject()) },
        )
        files.contents[partner] = realLog("partner-session", 3)
        // One real observation, then forty-nine suppressed ones (unchanged), then a few more
        // suppressed by ownership and by extension.
        repeat(50) { watcher.drain() }
        files.contents[mine] = realLog("mine", 2)
        files.contents["$partner.meta"] = "{}".toByteArray()
        repeat(5) { watcher.drain() }
        host.emit("doc.change", buildJsonObject { put("x", 1) })

        val observed = entries.filter { it.kind == "peer.observed" }
        assertEquals(1, observed.size)
        assertEquals(3, entries.size)
        // Dense from 0, and the chain links up: no gap, so no `seq_gaps` and no
        // `chain_integrity` finding.
        assertEquals(entries.indices.map { it.toLong() }, entries.map { it.seq })
        assertEquals(ChainCheck.Valid, validateChain(entries))
    }

    /**
     * EVERY READ HAPPENS BEFORE EVERY EMIT, in one drain.
     *
     * This is the Kotlin spelling of the rule VS Code writes as "every await is above the
     * chain-advance seam". Hashing and parsing a multi-megabyte foreign log BETWEEN two chain
     * advances puts an unbounded I/O operation inside a run of `seq` allocations, which is how
     * an emitter starves the others and how a batch of related observations gets scattered
     * through an unrelated event stream.
     *
     * Asserted structurally rather than by timing, because a timing assertion about "the
     * entries came out together" is a flake waiting to happen: the journal below records the
     * ORDER of the two kinds of operation, so moving the `emit` inside the observe loop fails
     * deterministically and on every machine.
     */
    @Test
    fun `one drain does all of its reading before any of its emitting`() {
        val journal = mutableListOf<String>()
        val logs = listOf("session-a.slog", "session-b.slog", "session-c.slog")
        val journaling = object : PeerFiles {
            override fun list(): List<String> = logs
            override fun read(name: String): ForeignLogRead {
                journal.add("read:$name")
                return ForeignLogRead.Bytes(realLog("s-$name", 2))
            }
        }
        val watcher = PeerWatcher(
            files = journaling,
            isOwnFile = { false },
            emit = { journal.add("emit:${it.file}") },
        )
        watcher.drain()

        assertEquals(
            listOf(
                "read:session-a.slog",
                "read:session-b.slog",
                "read:session-c.slog",
                "emit:session-a.slog",
                "emit:session-b.slog",
                "emit:session-c.slog",
            ),
            journal,
        )
    }

    /**
     * THE SESSIONHOST SEAM. A watcher ADDS AN EMITTER, and the `SessionHost` critical section
     * is the only thing standing between that and a manufactured tamper finding.
     *
     * Before `emit` was synchronized, two emitters could read the same `prevHash` and the loser
     * would write an entry whose `prev_hash` did not match its predecessor's `hash` — which is
     * INDISTINGUISHABLE from a deleted or tampered entry to validation. This drives the peer
     * watcher's drain concurrently with the other production emitters (the document path, the
     * heartbeat, the git executor) and requires a dense, valid chain.
     *
     * `SessionHost` itself is untouched by this change; this pins the seam the change leans on.
     */
    @Test
    fun `the peer watcher interleaved with other emitters keeps the chain valid and dense`() {
        val entries = Collections.synchronizedList(mutableListOf<HashedEnvelope>())
        val host = createSessionHost("mine", FixedClock(0)) { entries.add(it) }

        // A directory whose contents keep changing, so every drain has something to emit.
        val revision = java.util.concurrent.atomic.AtomicInteger(0)
        val churning = object : PeerFiles {
            override fun list(): List<String> = listOf(partner)
            override fun read(name: String): ForeignLogRead =
                ForeignLogRead.Bytes(realLog("partner-session", revision.incrementAndGet()))
        }
        val watcher = PeerWatcher(
            files = churning,
            isOwnFile = { false },
            emit = { host.emit("peer.observed", it.toJsonObject()) },
        )

        val threads = 8
        val perThread = 40
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    // Two of the eight threads are the peer watcher (a checkpoint drain and a
                    // teardown drain racing); the rest are the ordinary emitters.
                    if (t < 2) {
                        watcher.drain()
                    } else {
                        host.emit("doc.change", buildJsonObject { put("t", "$t-$i") })
                    }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("emitters must finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        val ordered = entries.toList()
        assertTrue(
            "the peer watcher must actually have emitted, or this proves nothing",
            ordered.count { it.kind == "peer.observed" } > 0,
        )
        assertEquals((threads - 2) * perThread, ordered.count { it.kind == "doc.change" })
        // Entries reach the writer in the SAME order they were chained.
        assertEquals(ordered.indices.map { it.toLong() }, ordered.map { it.seq })
        assertEquals(ChainCheck.Valid, validateChain(ordered))
    }

    /**
     * Two drains never interleave their reads. A checkpoint drain landing while the teardown
     * drain runs must produce one observation per change, not two — a duplicate witness is a
     * duplicate assertion about somebody else's artifact.
     */
    @Test
    fun `concurrent drains do not double-observe one change`() {
        val files = FakeFiles()
        val out = Collected()
        val emitted = Collections.synchronizedList(mutableListOf<PeerObservedPayload>())
        val watcher = PeerWatcher(
            files = files,
            isOwnFile = { false },
            emit = { emitted.add(it) },
            onError = { out.errors.add(it) },
        )
        files.contents[partner] = realLog("partner-session", 6)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(20) { watcher.drain() }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("one change, one observation", 1, emitted.size)
        assertEquals(emptyList<Throwable>(), out.errors)
    }

    // -----------------------------------------------------------------------
    // The emitted shape, against the shared vector
    // -----------------------------------------------------------------------

    /**
     * Every state this port can produce is one the shared vector publishes, and every payload
     * it produces narrows through the SHARED reader. The canonical bytes and chain hashes are
     * pinned case by case in `core`'s `ConformanceTest.PeerObservedVectors`, which builds each
     * accepted case through the same `PeerObservedPayload.toJsonObject()` this watcher's output
     * goes through; what is added here is that the WATCHER actually reaches all five.
     */
    @Test
    fun `the watcher reaches all five states and every payload narrows`() {
        val files = FakeFiles()
        val out = Collected()
        val watcher = watcherOver(files, out)

        files.contents[partner] = realLog("partner-session", 2) // appeared
        watcher.drain()
        files.contents[partner] = realLog("partner-session", 30) // grew
        watcher.drain()
        files.contents[partner] = realLog("partner-session", 1) // shrank
        watcher.drain()
        files.contents[partner] = "garbage\n".toByteArray() // unparseable
        watcher.drain()
        files.contents.remove(partner) // disappeared
        watcher.drain()

        assertEquals(
            PeerObservedState.entries.map { it.wire }.toSet(),
            out.payloads.map { it.state.wire }.toSet(),
        )
        for (p in out.payloads) {
            assertTrue(
                "${p.state} must narrow",
                validatePeerObservedPayload(p.toJsonObject()) is PeerObservedParse.Ok,
            )
        }
        // The `disappeared` observation inherits the LAST state seen — which here is the
        // unparseable one, all-nulls and all — rather than reaching back to a stale parsed tip.
        val gone = out.payloads.last()
        assertEquals(PeerObservedState.DISAPPEARED, gone.state)
        assertNull(gone.sessionId)
        assertNull(gone.seqHigh)
        assertNull(gone.lastHash)
    }
}
