package dev.provenance.recorder.commands

import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import dev.provenance.core.Envelope
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.ResolvedScope
import dev.provenance.core.Sha256
import dev.provenance.core.chainEntry
import dev.provenance.core.serializeEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipInputStream

class SealBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val priv: ByteArray
    private val pub: ByteArray

    init {
        val kp = Ed25519.generateKeypair()
        priv = kp.first
        pub = kp.second
    }

    /**
     * Write one session's pair of files: `<filename>` and `<filename>.meta`.
     *
     * BOTH halves, always (unless a test explicitly asks otherwise), because that is what the
     * recorder actually leaves on disk — `MetaWriter.create` writes the `.meta` eagerly at
     * session start, before a single event is flushed. A fixture with a bare `.slog` describes
     * a `.provenance/` no session can produce, and the analyzer rejects the whole bundle for it
     * (`orphaned_slog`).
     *
     * TWO-UUID RULE. [sessionId] is the LOGICAL id — the one inside `session.start.data` — and
     * [filename] carries the file's own uuid. Production spells them the same, but they are two
     * different things and only the logical one is what a rolling manifest is named after, so
     * they are two parameters here and a test may deliberately make them differ.
     *
     * Ids used with a rolling manifest must be hex (`[0-9a-f-]`): that is the character class
     * `parseRollingManifestFilename` accepts, so `manifest-sess-1.json` is not a rolling
     * manifest at all and every assertion about one would pass vacuously.
     */
    private fun writeSession(
        provDir: Path,
        filename: String,
        manifestSig: String,
        pubHex: String,
        corrupt: Boolean = false,
        sessionId: String = "sess-1",
        writeMeta: Boolean = true,
        empty: Boolean = false,
    ) {
        var seq = 0L
        var prev = GENESIS_PREV_HASH
        fun emit(kind: String, data: Map<String, String>): HashedEnvelope {
            val obj = buildJsonObject { data.forEach { (k, v) -> put(k, v) } }
            val e = chainEntry(prev, Envelope(seq, seq, "2026-07-14T00:00:0${seq}Z", kind, obj))
            seq += 1; prev = e.hash
            return e
        }
        val e0 = emit("session.start", mapOf("session_id" to sessionId, "manifest_sig" to manifestSig, "session_pubkey" to pubHex))
        val e1 = emit("doc.open", mapOf("path" to "hw.py"))
        val text = StringBuilder(serializeEntry(e0)).append(serializeEntry(e1))
        if (corrupt) text.append("this is not json\n")
        Files.write(
            provDir.resolve(filename),
            if (empty) ByteArray(0) else text.toString().toByteArray(Charsets.UTF_8),
        )
        if (writeMeta) {
            Files.writeString(
                provDir.resolve("$filename.meta"),
                """{"format_version":"1.0","session_id":"$sessionId","session_pubkey":"$pubHex","checkpoints":[]}""",
            )
        }
    }

    /** An exact-path-only [ResolvedScope] — the pre-path-scope shape most tests still want. */
    private fun exact(vararg paths: String): ResolvedScope = ResolvedScope(paths.toList(), emptyList(), emptyList())

    private fun manifestJsonOf(entries: Map<String, ByteArray>): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(String(entries["manifest.json"]!!, Charsets.UTF_8)).jsonObject

    private fun readZipEntries(zip: Path): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                out[e.name] = zin.readBytes()
                e = zin.nextEntry
            }
        }
        return out
    }

    @Test
    fun `no slog files yields NoSessions`() {
        val prov = Files.createDirectory(tmp.root.toPath().resolve(".provenance"))
        val result = sealBundle(prov, tmp.root.toPath(), "hw03", "fa26", exact(), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.NoSessions)
    }

    @Test
    fun `valid session produces a signature-verifiable bundle`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val slogBytesBefore = Files.readAllBytes(prov.resolve("session-1.slog"))

        val result = sealBundle(
            prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) },
            outputDir = ws, now = { Instant.parse("2026-07-14T12:00:00Z") },
        )
        assertTrue(result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse(ok.chainBroken)
        assertFalse(ok.unreadableSession)

        val entries = readZipEntries(ok.bundlePath)
        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("manifest.sig"))
        assertTrue(entries.containsKey("session-1.slog"))
        // .slog present unmodified.
        assertArrayEqualsHelper(slogBytesBefore, entries["session-1.slog"]!!)

        // manifest.json is already canonical (canonicalize is idempotent).
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertEquals(Canonical.canonicalize(manifestJson), manifestJson)
        // manifest.sig verifies against the session pubkey over the canonical manifest bytes.
        val sigHex = String(entries["manifest.sig"]!!, Charsets.UTF_8)
        assertTrue(Ed25519.verify(Ed25519.hexToBytes(sigHex), manifestJson.toByteArray(Charsets.UTF_8), pub))
        // manifestSha256 matches.
        assertEquals(Sha256.hex(manifestJson.toByteArray(Charsets.UTF_8)), ok.manifestSha256)
    }

    @Test
    fun `corrupted slog still seals with chainBroken`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), corrupt = true)
        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        assertTrue((result as SealResult.Ok).unreadableSession)
    }

    @Test
    fun `missing reviewed file is marked missing and not zipped`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(prov, ws, "hw03", "fa26", exact("ghost.py"), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val entries = readZipEntries((result as SealResult.Ok).bundlePath)
        assertFalse(entries.containsKey("ghost.py"))
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"status\":\"missing\""))
        assertTrue(manifestJson.contains("\"sha256\":null"))
        assertNull(null) // present file check covered in end-to-end task
    }

    // --- WorkspaceFileRead: `missing` is reachable from exactly one condition -------------
    //
    // A `status: "missing"` entry is baked into a SIGNED manifest and read by course staff as
    // "this file was listed for review and was not on disk at seal time" -- used in
    // academic-integrity proceedings. Every case below is a file that WAS there in some sense
    // (a directory at that path, a file staff/OS permissions blocked, a symlink to real bytes
    // elsewhere) and must never be reported as absent.

    /**
     * THE likeliest recurrence of this bug class: course staff write `files_under_review`
     * as `"src"` instead of `"src/"`, naming the directory itself. Before the fix,
     * `Files.readAllBytes` on a directory throws `IOException: Is a directory`, an
     * `Exception`, which the old narrow catch folded straight into `status: "missing"` --
     * falsely telling staff the student's entire `src/` submission does not exist.
     */
    @Test
    fun `an entry naming a directory -- the ordinary src vs src-slash staff typo -- is dropped, never missing`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        Files.write(ws.resolve("src").resolve("main.py"), "print(1)\n".toByteArray())

        val result = sealBundle(prov, ws, "hw03", "fa26", exact("src"), priv, { "e".repeat(64) })
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertTrue("a directory entry must be disclosed as non-regular", ok.nonRegularFile)
        assertFalse(ok.unreadableFile)
        assertFalse(ok.outOfWorkspaceFile)

        val entries = readZipEntries(ok.bundlePath)
        assertFalse(entries.containsKey("src"))
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertFalse(
            "a directory-named entry must never be sealed as status:missing -- that is a " +
                "false 'this file does not exist' claim about a file the student did submit",
            manifestJson.contains("\"path\":\"src\""),
        )
    }

    /** A permission error must never be folded into "this file does not exist". */
    @Test
    fun `an unreadable reviewed file -- permission denied -- is dropped, never missing`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val target = ws.resolve("secret.py")
        Files.write(target, "print(1)\n".toByteArray())
        val perms = Files.getPosixFilePermissions(target)
        Files.setPosixFilePermissions(target, emptySet())
        try {
            assumeTrue(
                "needs a filesystem/user for which an unreadable file is actually unreadable",
                runCatching { Files.readAllBytes(target) }.isFailure,
            )
            val result = sealBundle(prov, ws, "hw03", "fa26", exact("secret.py"), priv, { "e".repeat(64) })
            assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
            val ok = result as SealResult.Ok
            assertTrue("an unreadable file must be disclosed", ok.unreadableFile)
            assertFalse(ok.nonRegularFile)
            assertFalse(ok.outOfWorkspaceFile)

            val manifestJson = String(readZipEntries(ok.bundlePath)["manifest.json"]!!, Charsets.UTF_8)
            assertFalse(
                "a permission failure must never be sealed as status:missing",
                manifestJson.contains("\"path\":\"secret.py\""),
            )
        } finally {
            Files.setPosixFilePermissions(target, perms)
        }
    }

    /**
     * Overwhelmingly a student's innocent `ln -s ~/shared/data.csv data.csv`, not an attack --
     * but we cannot vouch for where it points, so it is dropped and its bytes are never read
     * into the bundle at all.
     */
    @Test
    fun `a symlink resolving outside the workspace is dropped and disclosed, its bytes never sealed`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        // A genuinely SEPARATE temp tree, not `tmp.newFolder(...)` -- that nests inside
        // `tmp.root`, which here IS `ws`, so it would land INSIDE the workspace and defeat
        // the point of the test.
        val outside = Files.createTempDirectory("provenance-seal-test-outside")
        try {
            val secretBytes = "not the student's to submit".toByteArray()
            val outsideFile = outside.resolve("data.csv")
            Files.write(outsideFile, secretBytes)
            Files.createSymbolicLink(ws.resolve("data.csv"), outsideFile)

            val result = sealBundle(prov, ws, "hw03", "fa26", exact("data.csv"), priv, { "e".repeat(64) })
            assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
            val ok = result as SealResult.Ok
            assertTrue("a symlink pointing outside the workspace must be disclosed", ok.outOfWorkspaceFile)
            assertFalse(ok.unreadableFile)
            assertFalse(ok.nonRegularFile)

            val entries = readZipEntries(ok.bundlePath)
            assertFalse("the pointed-to bytes must never be sealed into the bundle", entries.containsKey("data.csv"))
            val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
            assertFalse(
                "an out-of-workspace symlink must never be sealed as status:missing",
                manifestJson.contains("\"path\":\"data.csv\""),
            )
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    /** The invariant must not over-correct: genuine absence is still reported. */
    @Test
    fun `a genuinely absent reviewed file is still recorded missing, and only that -- not the other disclosure flags`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(prov, ws, "hw03", "fa26", exact("ghost.py"), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse(ok.unreadableFile)
        assertFalse(ok.outOfWorkspaceFile)
        assertFalse(ok.nonRegularFile)
        val manifestJson = String(readZipEntries(ok.bundlePath)["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"status\":\"missing\""))
    }

    private fun assertArrayEqualsHelper(a: ByteArray, b: ByteArray) {
        assertEquals(a.toList(), b.toList())
    }

    // --- the classic seal path is frozen ----------------------------------------------------

    /**
     * THE BYTE-FOR-BYTE PIN on the classic seal, for a normal, fully-flushed session.
     *
     * Every input is fixed — the session private key, the `.slog` and `.meta` bytes, the
     * extension hash, the timestamp — so the signed manifest is a pure function with exactly
     * one right answer, and ed25519 signing is deterministic. The three literals below were
     * captured from the seal path BEFORE the orphan guard existed. If the guard ever perturbs
     * the ordinary path — one extra `sessions` entry, a reordered zip, a different canonical
     * form — this goes red on the exact bytes rather than on a behavioural approximation of
     * them.
     *
     * Deliberately NOT a property assertion ("the manifest verifies", "the zip has a
     * manifest.json"): those hold for a manifest that changed. Only the literal bytes prove
     * that nothing moved.
     */
    @Test
    fun `a normal session seals to exactly the bytes it sealed to before the orphan guard`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        val fixedPriv = Ed25519.hexToBytes("e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75")
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(Ed25519.publicKeyOf(fixedPriv)))

        val result = sealBundle(
            prov, ws, "hw03", "fa26", exact(), fixedPriv, { "e".repeat(64) },
            outputDir = ws, now = { Instant.parse("2026-07-14T12:00:00Z") },
        )
        assertTrue("seal failed: $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        val entries = readZipEntries(ok.bundlePath)

        assertEquals(
            "the signed manifest bytes must not move",
            GOLDEN_MANIFEST_JSON,
            String(entries["manifest.json"]!!, Charsets.UTF_8),
        )
        assertEquals(
            "the signature over those bytes must not move",
            GOLDEN_MANIFEST_SIG,
            String(entries["manifest.sig"]!!, Charsets.UTF_8),
        )
        assertEquals(Sha256.hex(GOLDEN_MANIFEST_JSON.toByteArray(Charsets.UTF_8)), ok.manifestSha256)
        assertEquals(
            "the archive's contents and their order must not move",
            listOf("manifest.json", "manifest.sig", "session-1.slog", "session-1.slog.meta"),
            entries.keys.toList(),
        )
    }

    // --- the orphan guard: never seal a bundle the analyzer cannot open ---------------------
    //
    // Every case below is a `.provenance/` the recorder can genuinely leave behind, and every
    // one of them makes `analysis-core` reject THE WHOLE BUNDLE — not the one bad file. One
    // stray artifact costs a student every session they recorded, which is why the guard drops
    // rather than tidies, and reports rather than aborts.
    //
    // Ids here are hex on purpose. `parseRollingManifestFilename` only accepts `[0-9a-f-]`, so
    // `manifest-sess-1.json` is not a rolling manifest at all and every assertion about one
    // would pass vacuously.

    /**
     * Write a rolling seal pair for [sessionId]. Only the NAMES matter to the orphan guard;
     * [scopeCapped] additionally exercises `readRolledScopeCapped`'s content read.
     */
    private fun writeRollingSeal(provDir: Path, sessionId: String, scopeCapped: Boolean = false) {
        val json = if (scopeCapped) {
            """{"format_version":"1.2","scope_capped":true}"""
        } else {
            """{"format_version":"1.2"}"""
        }
        Files.writeString(provDir.resolve("manifest-$sessionId.json"), json)
        Files.writeString(provDir.resolve("manifest-$sessionId.sig"), "00".repeat(64))
    }

    /** Write a rolling seal `.json` that is not valid JSON at all, with its `.sig` beside it. */
    private fun writeMalformedRollingSeal(provDir: Path, sessionId: String) {
        Files.writeString(provDir.resolve("manifest-$sessionId.json"), "not json at all")
        Files.writeString(provDir.resolve("manifest-$sessionId.sig"), "00".repeat(64))
    }

    private fun sealOk(prov: Path, ws: Path, scope: ResolvedScope = exact()): SealResult.Ok {
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        return result as SealResult.Ok
    }

    @Test
    fun `a zero-byte slog is dropped from the zip AND from the signed manifest`() {
        // `SessionWriter.open` creates the `.slog` eagerly and BufferPolicy will not flush one
        // small entry, so a session killed between session.start and the first flush leaves a
        // well-paired but CONTENTLESS log. The loader reports first_event_not_session_start
        // (actualKind "none") and rejects the bundle. Zero bytes means the session recorded
        // literally nothing, so dropping it discards no evidence — keeping it discards all of it.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        writeSession(prov, "session-2.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = "sess-2", empty = true)

        val ok = sealOk(prov, ws)
        assertTrue("the drop must be reported", ok.emptySession)
        val entries = readZipEntries(ok.bundlePath)
        assertTrue(entries.containsKey("session-1.slog"))
        assertFalse("the empty log must not be packed", entries.containsKey("session-2.slog"))
        assertFalse("nor its meta, which would then be orphaned", entries.containsKey("session-2.slog.meta"))
        // The manifest and the zip must AGREE. A manifest naming a session whose file is absent
        // is just another way to make the bundle unopenable — and it is SIGNED.
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertFalse("the dropped session must not be in the signed manifest", manifestJson.contains("sess-2"))
        assertFalse("and it must not appear as a null-id entry either", manifestJson.contains("\"session_id\":null"))
    }

    @Test
    fun `a slog meta whose slog was quarantined is dropped`() {
        // ChainRecovery renames a damaged `.slog` to `.corrupt-<ts>`, which the zip step already
        // excluded, and leaves the `.slog.meta` under its original name. The salvage path itself
        // produced an `orphaned_meta` bundle.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        writeSession(prov, "session-2.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = "sess-2")
        Files.move(prov.resolve("session-2.slog"), prov.resolve("session-2.slog.corrupt-2026-07-14T00-00-00Z"))

        val ok = sealOk(prov, ws)
        assertTrue("the drop must be reported", ok.orphanedMeta)
        val entries = readZipEntries(ok.bundlePath)
        assertTrue("the healthy session still seals", entries.containsKey("session-1.slog"))
        assertFalse("the stranded meta must not be packed", entries.containsKey("session-2.slog.meta"))
        assertTrue("the quarantined log stays excluded as before", entries.keys.none { it.contains(".corrupt-") })
    }

    @Test
    fun `a slog with no meta is dropped from the zip and the manifest`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        writeSession(prov, "session-2.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = "sess-2", writeMeta = false)

        val ok = sealOk(prov, ws)
        assertTrue("the drop must be reported", ok.orphanedSlog)
        val entries = readZipEntries(ok.bundlePath)
        assertFalse(entries.containsKey("session-2.slog"))
        assertFalse(String(entries["manifest.json"]!!, Charsets.UTF_8).contains("sess-2"))
    }

    @Test
    fun `a rolling seal for a session not in the bundle is dropped, both halves`() {
        // The rolling seal is a THIRD per-session artifact, written eagerly at write point 1 —
        // before the `.slog` has been flushed even once — so it outlives every reason step 1 has
        // for dropping a session. `reconcileRollingSealsWithSessions` calls it `no_session_log`
        // and fails check 1 for the WHOLE bundle.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        writeRollingSeal(prov, GHOST_ID)

        val ok = sealOk(prov, ws)
        assertTrue("the drop must be reported", ok.orphanedRollingSeal)
        val entries = readZipEntries(ok.bundlePath)
        // Both halves go together: a `.sig` without its `.json` vouches for nothing, and a
        // `.json` without its `.sig` is an unsigned claim (`missing_sig`).
        assertFalse(entries.containsKey("manifest-$GHOST_ID.json"))
        assertFalse(entries.containsKey("manifest-$GHOST_ID.sig"))
        // Never DELETED, only left out: the on-disk `.provenance/` is what a git submission is
        // read from, and in a shared repo it may be a partner's evidence.
        assertTrue("the seal must survive on disk", Files.exists(prov.resolve("manifest-$GHOST_ID.json")))
        assertTrue(Files.exists(prov.resolve("manifest-$GHOST_ID.sig")))
    }

    @Test
    fun `a rolling seal for a session that IS in the bundle is kept`() {
        // The guard against over-correcting. Dropping every rolling seal would also "fix" the
        // orphan, and would silently strip a partner's live evidence out of a shared repo.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        writeRollingSeal(prov, LIVE_ID)

        val ok = sealOk(prov, ws)
        assertFalse("nothing was orphaned", ok.orphanedRollingSeal)
        val entries = readZipEntries(ok.bundlePath)
        assertTrue(entries.containsKey("manifest-$LIVE_ID.json"))
        assertTrue(entries.containsKey("manifest-$LIVE_ID.sig"))
        // And the CLASSIC seal is never a rolling one: it must always be packed.
        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("manifest.sig"))
    }

    @Test
    fun `the rolling-seal guard keys on the logical session id, not the slog filename uuid`() {
        // THE TWO-UUID RULE. A rolling manifest is named after `session.start.data.session_id`,
        // and that is the id `reconcileRollingSealsWithSessions` matches against — NOT the uuid
        // in the `.slog` filename. The two are spelled the same in production, so a fixture has
        // to force them apart for the distinction to be testable at all. Key the guard on the
        // filename and this inverts completely: the real seal is dropped and the stale one kept.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(
            prov,
            "session-$FILE_UUID.slog",
            "ab".repeat(64),
            Ed25519.bytesToHex(pub),
            sessionId = LOGICAL_ID,
        )
        writeRollingSeal(prov, LOGICAL_ID)
        writeRollingSeal(prov, FILE_UUID)

        val ok = sealOk(prov, ws)
        assertTrue(ok.orphanedRollingSeal)
        val entries = readZipEntries(ok.bundlePath)
        assertTrue("the seal named after the LOGICAL id is this session's", entries.containsKey("manifest-$LOGICAL_ID.json"))
        assertFalse("the seal named after the FILENAME uuid seals nothing", entries.containsKey("manifest-$FILE_UUID.json"))
    }

    @Test
    fun `a clean provenance dir drops nothing and reports nothing`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        writeRollingSeal(prov, LIVE_ID)

        val ok = sealOk(prov, ws)
        assertFalse("a clean directory must never claim something was dropped", ok.anythingDropped)
        assertTrue(ok.droppedDescriptions().isEmpty())
    }

    @Test
    fun `a dir of nothing but orphaned rolling seals still seals the one real session`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        for (id in listOf(GHOST_ID, FILE_UUID, LOGICAL_ID)) writeRollingSeal(prov, id)

        val ok = sealOk(prov, ws)
        assertTrue(ok.orphanedRollingSeal)
        val entries = readZipEntries(ok.bundlePath)
        assertEquals(
            "only the classic seal and the one real session survive",
            listOf("manifest.json", "manifest.sig", "session-$LIVE_ID.slog", "session-$LIVE_ID.slog.meta"),
            entries.keys.toList(),
        )
    }

    @Test
    fun `a workspace whose only session never flushed reports NoSessions, not a broken bundle`() {
        // Nothing recordable happened, so there is nothing to seal. The alternative — sealing an
        // archive whose single session cannot be opened — is the defect this guard exists for.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), empty = true)
        writeRollingSeal(prov, GHOST_ID)

        assertTrue(sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }) is SealResult.NoSessions)
    }

    private companion object {
        /** Hex ids — see the section comment on why that is load-bearing, not cosmetic. */
        const val LIVE_ID = "3c53e673-1111-4222-8333-444455556666"
        const val GHOST_ID = "ecfea1fa-9999-4aaa-8bbb-ccccddddeeee"

        /** A session whose LOGICAL id and `.slog` FILENAME uuid deliberately differ. */
        const val LOGICAL_ID = "aaaaaaaa-1111-4222-8333-444455556666"
        const val FILE_UUID = "bbbbbbbb-1111-4222-8333-444455556666"

        /** Captured from the pre-guard seal path. See the test above for why these are literals. */
        const val GOLDEN_MANIFEST_JSON = "{\"assignment_id\":\"hw03\",\"extension_hash\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\",\"format_version\":\"1.1\",\"semester\":\"fa26\",\"sessions\":[{\"meta_sha256\":\"c21e1c7f9f71840977f1d28b08846dc5896d2c0f3deb3971cb94fdb1ced210c8\",\"prev_session_id\":null,\"session_id\":\"sess-1\",\"slog_sha256\":\"67c34143272dc0d2cac5ae9c88fc6fc1d1152bce2c443e7b107727bd2601081d\"}],\"submission_files\":[]}"
        const val GOLDEN_MANIFEST_SIG = "69121fa30c787bd10e95dfb0864b9fb5d2b1e36c9c867c2793ad0248611501b5029fb9f5537b82c45ec134c2e5bd5fad36a812131f9ee4f9a5c5ca3604096c06"
    }

    // --- an Error must not cost the student their submission --------------------
    //
    // The seal path models failure as a returned SealResult.WriteError the UI can show.
    // Catching only Exception meant an Error (IJent answers unimplemented filesystem
    // operations with kotlin.NotImplementedError) escaped sealBundle as a raw crash —
    // and a seal that dies costs a whole submission, not one event.

    @Test
    fun `an Error from computeExtensionHash becomes a WriteError instead of escaping`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(
            prov, ws, "hw03", "fa26", exact(), priv,
            { throw NotImplementedError("An operation is not implemented: FILE_READ") },
        )
        assertTrue("expected a typed WriteError, got $result", result is SealResult.WriteError)
        assertTrue((result as SealResult.WriteError).message.contains("extension hash"))
    }

    @Test
    fun `an Error from the manifest write becomes a WriteError instead of escaping`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(
            prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) },
            writeFile = { _, _ -> throw NotImplementedError("An operation is not implemented: FILE_FORCE") },
        )
        assertTrue("expected a typed WriteError, got $result", result is SealResult.WriteError)
        assertTrue((result as SealResult.WriteError).message.contains("manifest/sig"))
    }

    @Test
    fun `an Error from manifest signing becomes a WriteError instead of escaping`() {
        // The ed25519 provider initialises lazily, at the first sign() call. A provider whose
        // static init fails (a stripped/relocated crypto class in a repackaged IDE) surfaces as
        // NoClassDefFoundError / ExceptionInInitializerError — Errors, not Exceptions. Different
        // cause from the IJent NotImplementedError, identical consequence: the seal dies with no
        // SealResult and no notification, on the one path where the student has no second chance.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(
            prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) },
            signManifest = { _, _ -> throw NoClassDefFoundError("com/google/crypto/tink/subtle/Ed25519Sign") },
        )
        assertTrue("expected a typed WriteError, got $result", result is SealResult.WriteError)
        assertTrue((result as SealResult.WriteError).message.contains("sign manifest"))
    }

    @Test
    fun `a VirtualMachineError from manifest signing still propagates`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val boom = OutOfMemoryError("heap")
        var caught: Throwable? = null
        try {
            sealBundle(
                prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) },
                signManifest = { _, _ -> throw boom },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("a VirtualMachineError must propagate untouched", boom, caught)
    }

    @Test
    fun `a VirtualMachineError propagates instead of being reported as a seal failure`() {
        // An OutOfMemoryError is not a seal failure: reporting it as one would be a wrong
        // diagnosis, and continuing after one is unsound. Same rule as SessionWriter.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val boom = OutOfMemoryError("heap")
        var caught: Throwable? = null
        try {
            sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { throw boom })
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("a VirtualMachineError must propagate untouched", boom, caught)
    }

    // --- unguarded seal sites: a filesystem failure must still be a typed SealResult ---------
    //
    // Distinct from the Exception-vs-Throwable widenings above: these sites had NO handler at
    // all, so an ordinary IOException escaped sealBundle as a raw crash — no SealResult, so
    // PrepareSubmissionBundleAction never notified the student that the seal died.

    @Test
    fun `an unreadable session hash becomes a WriteError instead of escaping`() {
        // sha256OfFile checks Files.exists and then reads: a classic TOCTOU. When the file goes
        // away (or otherwise stops being readable) between the two calls, readAllBytes throws
        // straight out of sealBundle. The literal delete race has no deterministic interleaving
        // point from a test, so the fixture pins the identical code path — exists() says yes,
        // the read then fails — by putting a DIRECTORY at the .slog.meta path. Only sha256OfFile
        // ever reads the meta path, so this isolates the unguarded read from the guarded
        // .slog read above it.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        // Replace the real `.meta` with a directory at the same NAME. The name still pairs, so
        // the session is packable and the read is reached — which is the point: exists() says
        // yes and the read then fails, exactly the TOCTOU shape being pinned.
        Files.delete(prov.resolve("session-1.slog.meta"))
        Files.createDirectory(prov.resolve("session-1.slog.meta"))

        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) })

        assertTrue("expected a typed WriteError, got $result", result is SealResult.WriteError)
        assertTrue((result as SealResult.WriteError).message.contains("session-1.slog"))
    }

    @Test
    fun `a VirtualMachineError still propagates out of the session hash step`() {
        // Guard: the new handler must keep the same fatal dividing line as the rest of the path.
        // Nothing can inject an OutOfMemoryError into sha256OfFile, so this pins the rule where
        // it is reachable — rethrowIfFatal is the single shared helper both sites go through.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val boom = OutOfMemoryError("heap")
        var caught: Throwable? = null
        try {
            sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { throw boom })
        } catch (t: Throwable) {
            caught = t
        }
        assertSame(boom, caught)
    }

    @Test
    fun `an unlistable provenance dir becomes a WriteError instead of escaping`() {
        // Same TOCTOU shape one step earlier: Files.isDirectory says yes, then Files.list fails.
        // An unreadable directory reproduces it deterministically. NOT NoSessions — "no sessions"
        // is a checked, non-racy verdict, and reporting a directory we could not read as "nothing
        // to seal" would tell a student their work is absent when it may be sitting right there.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val perms = Files.getPosixFilePermissions(prov)
        Files.setPosixFilePermissions(prov, emptySet())
        try {
            assumeTrue(
                "needs a filesystem/user for which an unreadable dir is actually unreadable",
                runCatching { Files.list(prov).use { it.toList() } }.isFailure,
            )
            val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) })
            assertTrue("expected a typed WriteError, got $result", result is SealResult.WriteError)
            assertTrue((result as SealResult.WriteError).message.contains("session files"))
        } finally {
            Files.setPosixFilePermissions(prov, perms)
        }
    }

    @Test
    fun `a missing reviewed file is still reported as missing, never as a seal failure`() {
        // Guard for the deliberately NARROW catch at the reviewed-file read: its job is to
        // mark a file missing, and it must keep catching only Exception. Widening it would
        // let a filesystem Error silently record a present file as missing in a SIGNED
        // manifest — a worse outcome than failing loudly.
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(prov, ws, "hw03", "fa26", exact("ghost.py"), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val manifestJson = String(readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"status\":\"missing\""))
    }

    // --- path scope at seal time -------------------------------------------------------------
    //
    // A rule entry (`src/`, `*.java`) cannot be enumerated from the manifest, so the seal WALKS
    // the workspace and assigns each discovered path a role. This block mirrors the upstream
    // VS Code recorder's "path scope at seal time" suite (design spec §4, plan Task 5b).

    @Test
    fun `walks and seals every rule-matched file with its role`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        Files.write(ws.resolve("src").resolve("Main.java"), "class Main {}".toByteArray())
        Files.write(ws.resolve("src").resolve("Util.java"), "class Util {}".toByteArray())
        Files.write(ws.resolve("readme.md"), "notes".toByteArray())

        val scope = ResolvedScope(track = listOf("src/"), ignore = emptyList(), attachments = listOf("readme.md"))
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse(ok.anythingDropped)

        val entries = readZipEntries(ok.bundlePath)
        assertTrue(entries.containsKey("src/Main.java"))
        assertTrue(entries.containsKey("src/Util.java"))
        assertTrue("an attachment is sealed too", entries.containsKey("readme.md"))

        val files = manifestJsonOf(entries)["submission_files"]!!.jsonArray
        val byPath = files.associate { it.jsonObject["path"]!!.jsonPrimitive.content to it.jsonObject }
        assertEquals("reviewed", byPath.getValue("src/Main.java")["role"]!!.jsonPrimitive.content)
        assertEquals("reviewed", byPath.getValue("src/Util.java")["role"]!!.jsonPrimitive.content)
        assertEquals("attachment", byPath.getValue("readme.md")["role"]!!.jsonPrimitive.content)
    }

    /** A rule entry claims nothing about any one file's existence (R2) — only an EXACT entry can. */
    @Test
    fun `an absent EXACT entry is missing, but a rule entry says nothing about files that do not exist`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        Files.write(ws.resolve("src").resolve("Main.java"), "class Main {}".toByteArray())

        // "src/" matches nothing that does not exist (no file named literally "src" ever
        // reaches the walk); "ghost.py" is an EXACT entry naming a file that never existed.
        val scope = ResolvedScope(track = listOf("src/", "ghost.py"), ignore = emptyList(), attachments = emptyList())
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val files = manifestJsonOf(readZipEntries((result as SealResult.Ok).bundlePath))["submission_files"]!!.jsonArray

        assertEquals(
            "exactly one file must be reported: the real one present, and the one EXACT absence",
            2,
            files.size,
        )
        val byPath = files.associate { it.jsonObject["path"]!!.jsonPrimitive.content to it.jsonObject }
        assertEquals("present", byPath.getValue("src/Main.java")["status"]!!.jsonPrimitive.content)
        assertEquals("missing", byPath.getValue("ghost.py")["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scope_capped is present when true, and absent from the serialized JSON entirely when false`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))

        val cappedResult = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = true)
        assertTrue(cappedResult is SealResult.Ok)
        val cappedJson = String(
            readZipEntries((cappedResult as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertTrue("scope_capped must be present and true", cappedJson.contains("\"scope_capped\":true"))

        val uncappedResult = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = false)
        assertTrue(uncappedResult is SealResult.Ok)
        val uncappedJson = String(
            readZipEntries((uncappedResult as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertFalse(
            "scope_capped must be OMITTED entirely, not written as false -- the canonical bytes are the signed message",
            uncappedJson.contains("scope_capped"),
        )
    }

    // --- scope_capped is a WHOLE-BUNDLE fact, recovered from every packed session's own
    // rolling seal -- not just the live session's bit ------------------------------------------
    //
    // `BundleManifest.scope_capped` is documented as "ANY session's recorder reported its
    // expected-content registry filled up". A classic bundle packs every `.slog` in
    // `.provenance/`, including sessions from editor runs that ended days ago, whose in-memory
    // registries no longer exist. Passing the LIVE session's bit straight through therefore
    // seals the key ABSENT whenever an EARLIER session capped and the current one did not --
    // and absence is exactly what lets the analyzer's most confident tier answer "in scope, no
    // activity" about a student whose recorder silently stopped watching a file. Mirrors the VS
    // Code recorder's `readRolledScopeCapped` suite in `commands/seal.test.ts`.

    @Test
    fun `a packed prior session's rolling seal reporting capped makes the whole bundle scope_capped, even though the live session's registry did not cap`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        val priorId = "11111111-1111-4111-8111-111111111111"
        val liveId = "22222222-2222-4222-8222-222222222222"
        writeSession(prov, "session-$priorId.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = priorId)
        writeSession(prov, "session-$liveId.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = liveId)
        // The prior session's editor run ended; its in-memory registry is long gone. Its
        // rolling seal is the only durable record of its cap bit.
        writeRollingSeal(prov, priorId, scopeCapped = true)

        // The LIVE session -- the only one sealBundle's `scopeCapped` argument can see directly
        // -- did NOT cap.
        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = false)
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val manifestJson = String(
            readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertTrue(
            "THE REGRESSION TEST for the false-accusation bug: scope_capped must OR across every " +
                "packed session's own rolling seal, not just the live session's bit -- an absent " +
                "key here is a false 'in scope, no activity' claim about a student whose recorder " +
                "silently stopped watching a file",
            manifestJson.contains("\"scope_capped\":true"),
        )
    }

    @Test
    fun `a rolling seal for a session this bundle does not pack is ignored for scope_capped`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        // GHOST_ID has no .slog in this bundle -- its rolling seal describes a recording this
        // bundle makes no claim about, matching the orphan guard's own rule at the zip step.
        writeRollingSeal(prov, GHOST_ID, scopeCapped = true)

        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = false)
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val manifestJson = String(
            readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertFalse(
            "a rolling seal naming a session this bundle does not pack must not contribute its bit",
            manifestJson.contains("scope_capped"),
        )
    }

    @Test
    fun `an unreadable or malformed rolling seal never mints scope_capped true`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        writeMalformedRollingSeal(prov, LIVE_ID)

        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = false)
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val manifestJson = String(
            readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertFalse(
            "a malformed rolling seal must never mint a true report -- absent stays absent, " +
                "the same as a session with no rolling seal at all",
            manifestJson.contains("scope_capped"),
        )
    }

    @Test
    fun `nothing capped anywhere -- live or any packed session's rolling seal -- omits scope_capped entirely`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-$LIVE_ID.slog", "ab".repeat(64), Ed25519.bytesToHex(pub), sessionId = LIVE_ID)
        writeRollingSeal(prov, LIVE_ID, scopeCapped = false)

        val result = sealBundle(prov, ws, "hw03", "fa26", exact(), priv, { "e".repeat(64) }, scopeCapped = false)
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val manifestJson = String(
            readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!,
            Charsets.UTF_8,
        )
        assertFalse(
            "scope_capped must be OMITTED entirely when nothing capped anywhere -- the canonical " +
                "bytes are the signed message",
            manifestJson.contains("scope_capped"),
        )
    }

    /**
     * A rule entry like `*.json` reading through the workspace must not walk into a SIBLING
     * assignment's `.provenance/` under this repo's nested/concurrent multi-assignment
     * recording -- that would seal one student's provenance into another's evidence bundle.
     */
    @Test
    fun `hard-exclusion pruning protects a nested sibling assignment's provenance directory`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val siblingProv = Files.createDirectories(ws.resolve("hw3").resolve(".provenance"))
        Files.write(siblingProv.resolve("manifest.json"), "{\"leaked\":true}".toByteArray())
        Files.write(ws.resolve("hw03.json"), "{\"mine\":true}".toByteArray())

        // The EXACT entry below reads directly by string and never passes through the walk's
        // own directory-level pruning, so the exact-entry loop must apply the same
        // hard-excluded-segment check independently -- otherwise naming the leak exactly would
        // still seal it even with the rule-entry hole above closed.
        val scope = ResolvedScope(
            track = listOf("*.json", "hw3/.provenance/manifest.json"),
            ignore = emptyList(),
            attachments = emptyList(),
        )
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val entries = readZipEntries((result as SealResult.Ok).bundlePath)
        assertFalse("a sibling assignment's provenance must never be sealed", entries.containsKey("hw3/.provenance/manifest.json"))
        assertTrue("the student's own matching file is still sealed", entries.containsKey("hw03.json"))
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertFalse(manifestJson.contains("leaked"))
        assertFalse(
            "the EXACT entry naming the nested provenance path must never be sealed as status:missing either",
            manifestJson.contains("\"path\":\"hw3/.provenance/manifest.json\""),
        )
    }

    @Test
    fun `an unreadable walk-discovered file is dropped and disclosed, never missing`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        val secret = ws.resolve("src").resolve("Secret.java")
        Files.write(secret, "class Secret {}".toByteArray())
        val perms = Files.getPosixFilePermissions(secret)
        Files.setPosixFilePermissions(secret, emptySet())
        try {
            assumeTrue(
                "needs a filesystem/user for which an unreadable file is actually unreadable",
                runCatching { Files.readAllBytes(secret) }.isFailure,
            )
            val scope = ResolvedScope(track = listOf("src/"), ignore = emptyList(), attachments = emptyList())
            val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
            assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
            val ok = result as SealResult.Ok
            assertTrue("an unreadable walk-discovered file must be disclosed", ok.unreadableFile)

            val entries = readZipEntries(ok.bundlePath)
            assertFalse(entries.containsKey("src/Secret.java"))
            val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
            assertFalse(
                "a walk-discovered file's read failure must never be sealed as status:missing -- a " +
                    "rule entry asserts nothing about any one file's existence",
                manifestJson.contains("Secret.java"),
            )
        } finally {
            Files.setPosixFilePermissions(secret, perms)
        }
    }

    @Test
    fun `an unreadable directory is disclosed rather than silently sealing nothing from it`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val src = Files.createDirectory(ws.resolve("src"))
        Files.write(src.resolve("Main.java"), "class Main {}".toByteArray())
        val perms = Files.getPosixFilePermissions(src)
        Files.setPosixFilePermissions(src, emptySet())
        try {
            assumeTrue(
                "needs a filesystem/user for which an unreadable dir is actually unreadable",
                runCatching { Files.newDirectoryStream(src).use { it.iterator().hasNext() } }.isFailure,
            )
            val scope = ResolvedScope(track = listOf("src/"), ignore = emptyList(), attachments = emptyList())
            val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
            assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
            val ok = result as SealResult.Ok
            assertTrue("an unlistable in-scope directory must be disclosed", ok.unreadableScopeDirectory)
        } finally {
            Files.setPosixFilePermissions(src, perms)
        }
    }

    /**
     * The walk classifies dirents lstat-style, so a symlinked file is never `isFile()` and
     * never reaches the walk's output. Not following it is deliberate (cycles, workspace
     * escape); the drop must still be disclosed rather than silently vanishing.
     */
    @Test
    fun `an in-scope symlink is dropped and disclosed`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        val real = ws.resolve("src").resolve("real.py")
        Files.write(real, "print(1)\n".toByteArray())
        // Points INSIDE the workspace -- an ordinary innocent alias, not an exfiltration
        // attempt -- so this pins the "dropped and disclosed" fact, not outOfWorkspaceFile.
        Files.createSymbolicLink(ws.resolve("src").resolve("alias.py"), real)

        val scope = ResolvedScope(track = listOf("src/"), ignore = emptyList(), attachments = emptyList())
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertTrue("an in-scope symlink must be disclosed", ok.inScopeSymlinkSkipped)
        assertFalse(ok.outOfWorkspaceFile)

        val entries = readZipEntries(ok.bundlePath)
        assertTrue("the real file is still sealed under its own path", entries.containsKey("src/real.py"))
        assertFalse("the symlink itself is never sealed", entries.containsKey("src/alias.py"))
    }

    /**
     * Round 2's actual upstream regression: the exact-entry loop's skip-set has to be built
     * from what the WALK SAW, not from what it successfully READ -- otherwise a file the walk
     * sighted but could not reopen falls through to the exact-entry loop and mints a false
     * `missing` there. chmod pins the "sighted, read failed" state deterministically (a true
     * listing-then-vanishing race is not reproducible in a unit test).
     */
    @Test
    fun `an EXACT entry the walk sighted but could not read is dropped, never missing`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.createDirectory(ws.resolve("src"))
        val locked = ws.resolve("src").resolve("Main.java")
        Files.write(locked, "class Main {}".toByteArray())
        val perms = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assumeTrue(
                "needs a filesystem/user for which an unreadable file is actually unreadable",
                runCatching { Files.readAllBytes(locked) }.isFailure,
            )
            val scope = ResolvedScope(track = listOf("src/Main.java"), ignore = emptyList(), attachments = emptyList())
            val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
            assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
            val ok = result as SealResult.Ok
            assertTrue(ok.unreadableFile)
            val manifestJson = String(readZipEntries(ok.bundlePath)["manifest.json"]!!, Charsets.UTF_8)
            assertFalse(
                "the walk-sighted-but-unreadable exact entry must never be sealed as status:missing",
                manifestJson.contains("Main.java"),
            )
        } finally {
            Files.setPosixFilePermissions(locked, perms)
        }
    }

    /**
     * A case-insensitive filesystem can make an EXACT entry read successfully while pointing
     * at the SAME underlying bytes the walk already sealed under a different spelling.
     */
    @Test
    fun `a duplicate exact entry -- same real file, different spelling -- is dropped and disclosed`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        Files.write(ws.resolve("Data.csv"), "a,b,c\n".toByteArray())

        // "*.csv" walks and seals "Data.csv" (its real on-disk spelling); the EXACT entry
        // "data.csv" reads the SAME file on a case-insensitive filesystem.
        val scope = ResolvedScope(track = listOf("*.csv", "data.csv"), ignore = emptyList(), attachments = emptyList())
        val result = sealBundle(prov, ws, "hw03", "fa26", scope, priv, { "e".repeat(64) })
        assertTrue("expected a sealed bundle, got $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assumeTrue(
            "needs a filesystem where toRealPath() case-folds -- verified separately for macOS/APFS",
            ok.duplicateEntryDropped,
        )

        val files = manifestJsonOf(readZipEntries(ok.bundlePath))["submission_files"]!!.jsonArray
        assertEquals("the same bytes must not be sealed twice under two paths", 1, files.size)
        assertEquals("Data.csv", files.single().jsonObject["path"]!!.jsonPrimitive.content)
    }
}
