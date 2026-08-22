package dev.provenance.recorder.io

import dev.provenance.core.BundleManifest
import dev.provenance.core.Ed25519
import dev.provenance.core.Envelope
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.RollingManifestPart
import dev.provenance.core.Sha256
import dev.provenance.core.SignedBundleManifest
import dev.provenance.core.chainEntry
import dev.provenance.core.parseRollingManifestFilename
import dev.provenance.core.rollingManifestFilenames
import dev.provenance.core.serializeEntry
import dev.provenance.core.signBundleManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rolling seal's write side (S3). The format bytes are pinned cross-language by
 * `core`'s ConformanceTest against `rolling-manifest.json`; this suite pins the FILE
 * behaviour that no vector can express — which names get written, which never do, what
 * survives a failure, and who is allowed to claim finality.
 */
class RollingSealTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sessionId = "33333333-3333-4333-8333-333333333333"
    private val priv: ByteArray
    private val pub: ByteArray

    init {
        val kp = Ed25519.generateKeypair()
        priv = kp.first
        pub = kp.second
    }

    private lateinit var wsRoot: Path
    private lateinit var provDir: Path
    private lateinit var slogPath: Path

    private fun setUpWorkspace(withMeta: Boolean = true) {
        wsRoot = tmp.newFolder("ws").toPath()
        provDir = Files.createDirectories(wsRoot.resolve(".provenance"))
        slogPath = provDir.resolve("session-$sessionId.slog")
        Files.write(slogPath, sessionStartLine().toByteArray(Charsets.UTF_8))
        if (withMeta) Files.write(provDir.resolve("session-$sessionId.slog.meta"), "{}".toByteArray())
        Files.write(wsRoot.resolve("hw.py"), "print(1)\n".toByteArray())
    }

    /** One real, chained ndjson line, so the `.slog` under test is a plausible log. */
    private fun sessionStartLine(kind: String = "session.start"): String =
        serializeEntry(
            chainEntry(
                GENESIS_PREV_HASH,
                Envelope(0, 0, "2026-07-14T00:00:00Z", kind, buildJsonObject { put("session_id", sessionId) }),
            ),
        )

    private fun roll(
        isFinal: Boolean = false,
        filesUnderReview: List<String> = listOf("hw.py", "missing.py"),
        signManifest: ((BundleManifest, ByteArray) -> SignedBundleManifest)? = null,
        writeFiles: ((List<Pair<Path, String>>) -> Unit)? = null,
    ): RollingSealResult = writeRollingSeal(
        provenanceDir = provDir,
        sessionId = sessionId,
        prevSessionId = null,
        slogPath = slogPath,
        workspaceRoot = wsRoot,
        assignmentId = "hw03",
        semester = "fa26",
        filesUnderReview = filesUnderReview,
        sessionPrivkey = priv,
        extensionHash = "1".repeat(64),
        isFinal = isFinal,
        signManifest = signManifest ?: ::signBundleManifest,
        writeFiles = writeFiles ?: ::atomicWriteFilePair,
    )

    private fun manifestJson(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            String(Files.readAllBytes(provDir.resolve(rollingManifestFilenames(sessionId).json)), Charsets.UTF_8),
        ).jsonObject

    // -----------------------------------------------------------------------
    // Names: per-session, add-only, and never the classic seal's
    // -----------------------------------------------------------------------

    /**
     * Per-session filenames are what make a shared repo's `.provenance/` add-only: two
     * partners' recorders write disjoint paths, so a merge is a union rather than a conflict
     * over one signed blob that neither student can hand-resolve.
     */
    @Test
    fun `writes manifest-sessionId json and sig, and no other file`() {
        setUpWorkspace()
        val before = listing()
        val result = roll()
        assertTrue("$result", result is RollingSealResult.Written)
        val names = rollingManifestFilenames(sessionId)
        assertEquals(before + setOf(names.json, names.sig), listing())
        assertTrue("no .tmp may survive: ${listing()}", listing().none { it.endsWith(".tmp") })
    }

    /**
     * `manifest-A.json` claiming session B would be verified against B's pubkey but read as
     * A's seal. The filename and the body come from ONE `sessionId` argument, so they cannot
     * disagree — asserted end to end, off the bytes actually on disk.
     */
    @Test
    fun `the filename and the session_id inside it always name the same session`() {
        setUpWorkspace()
        roll()
        val written = Files.list(provDir).use { s ->
            s.map { it.fileName.toString() }.filter { parseRollingManifestFilename(it) != null }.toList()
        }
        assertEquals(2, written.size)
        val json = written.single { parseRollingManifestFilename(it)!!.part == RollingManifestPart.JSON }
        assertEquals(
            parseRollingManifestFilename(json)!!.sessionId,
            manifestJson()["sessions"]!!.jsonArray.single().jsonObject["session_id"]!!.jsonPrimitive.content,
        )
    }

    /**
     * The classic seal is a different artifact with different rules, and it is SIGNED: this
     * path must never create, replace, or touch it. Pre-existing bytes are compared exactly.
     */
    @Test
    fun `a pre-existing classic seal is left byte-for-byte alone`() {
        setUpWorkspace()
        val classicJson = provDir.resolve("manifest.json")
        val classicSig = provDir.resolve("manifest.sig")
        val jsonBytes = "{\"classic\":true}".toByteArray()
        val sigBytes = "deadbeef".toByteArray()
        Files.write(classicJson, jsonBytes)
        Files.write(classicSig, sigBytes)

        roll()
        roll(isFinal = true)

        assertArrayEquals(jsonBytes, Files.readAllBytes(classicJson))
        assertArrayEquals(sigBytes, Files.readAllBytes(classicSig))
    }

    /** The complement: with no classic seal present, the rolling path never invents one. */
    @Test
    fun `never creates manifest json or manifest sig`() {
        setUpWorkspace()
        roll()
        roll(isFinal = true)
        assertFalse(Files.exists(provDir.resolve("manifest.json")))
        assertFalse(Files.exists(provDir.resolve("manifest.sig")))
        assertNull(parseRollingManifestFilename("manifest.json"))
        assertNull(parseRollingManifestFilename("manifest.sig"))
    }

    // -----------------------------------------------------------------------
    // Signature and shape
    // -----------------------------------------------------------------------

    /**
     * The bytes ON DISK are exactly what was signed, and they were signed by THIS session's
     * key — the one whose public half `session.start` records. A rolling seal is verified
     * against exactly one pubkey, so signing with anything else is a manufactured failure.
     */
    @Test
    fun `the sig file verifies over the json file's bytes, under this session's key only`() {
        setUpWorkspace()
        val names = rollingManifestFilenames(sessionId)
        roll()
        val jsonBytes = Files.readAllBytes(provDir.resolve(names.json))
        val sigHex = String(Files.readAllBytes(provDir.resolve(names.sig)), Charsets.UTF_8)
        assertTrue(Ed25519.verify(Ed25519.hexToBytes(sigHex), jsonBytes, pub))
        val stranger = Ed25519.generateKeypair().second
        assertFalse("must not verify under another session's key", Ed25519.verify(Ed25519.hexToBytes(sigHex), jsonBytes, stranger))
    }

    /**
     * Exactly one session per FILE. Two would need two private keys to be verifiable and
     * would reintroduce the shared-file merge conflict per-session names exist to remove.
     */
    @Test
    fun `covers exactly one session, at format_version 1_2`() {
        setUpWorkspace()
        roll()
        val m = manifestJson()
        assertEquals("1.2", m["format_version"]!!.jsonPrimitive.content)
        assertEquals(1, m["sessions"]!!.jsonArray.size)
        assertEquals(sessionId, m["sessions"]!!.jsonArray.single().jsonObject["session_id"]!!.jsonPrimitive.content)
    }

    /** Reviewed files: present ones hashed, absent ones recorded missing — never omitted. */
    @Test
    fun `records reviewed files in files_under_review order, missing ones as missing`() {
        setUpWorkspace()
        roll(filesUnderReview = listOf("hw.py", "missing.py"))
        val files = manifestJson()["submission_files"]!!.jsonArray
        assertEquals(2, files.size)
        assertEquals("hw.py", files[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("present", files[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(Sha256.hex("print(1)\n"), files[0].jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals("missing.py", files[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("missing", files[1].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, files[1].jsonObject["sha256"])
    }

    /**
     * A `git checkout` that removes the `.slog` mid-session must not fail the seal: an
     * unreadable log hashes as EMPTY, which is a well-formed 64-hex value rather than an
     * exception or an unwritable manifest.
     */
    @Test
    fun `an unreadable slog hashes as empty rather than failing the seal`() {
        setUpWorkspace()
        Files.delete(slogPath)
        Files.delete(provDir.resolve("session-$sessionId.slog.meta"))
        assertTrue(roll() is RollingSealResult.Written)
        val session = manifestJson()["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(Sha256.hex(ByteArray(0)), session["slog_sha256"]!!.jsonPrimitive.content)
        assertEquals(Sha256.hex(ByteArray(0)), session["meta_sha256"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // The `final` marker
    // -----------------------------------------------------------------------

    /**
     * Non-final is the shape of EVERY roll but the last: the key is ABSENT, not `false`.
     * `"final":false` would change the canonical bytes — the signed message — of every seal
     * in every student's repo and break byte-compatibility with the other two recorders.
     */
    @Test
    fun `a non-final roll omits the final key entirely`() {
        setUpWorkspace()
        val written = roll() as RollingSealResult.Written
        assertFalse("must OMIT final, not write false", written.canonicalJson.contains("final"))
        assertNull(manifestJson()["final"])
    }

    /** The teardown roll, over a finished log: whole-file semantics, claimed explicitly. */
    @Test
    fun `a final roll writes final true inside the signed payload`() {
        setUpWorkspace()
        val written = roll(isFinal = true) as RollingSealResult.Written
        assertTrue(written.canonicalJson.contains("\"final\":true"))
        assertEquals(true, manifestJson()["final"]!!.jsonPrimitive.content.toBoolean())
        // Inside the SIGNED payload: the signature is over exactly these bytes, so a student
        // cannot add, flip or strip the marker without this session's private key.
        assertTrue(
            Ed25519.verify(
                Ed25519.hexToBytes(written.signatureHex),
                written.canonicalJson.toByteArray(Charsets.UTF_8),
                pub,
            ),
        )
        assertNotEquals(
            "the two shapes must not share canonical bytes",
            written.canonicalJson,
            (roll(isFinal = false) as RollingSealResult.Written).canonicalJson,
        )
    }

    /**
     * **Finality is the CALLER's claim, never the writer's inference.**
     *
     * Here the `.slog` ends with a `session.end` entry — the exact state a crashed session
     * leaves behind after the event was emitted but before teardown completed. A writer that
     * sniffed the log for `session.end` would stamp this seal final and turn the next honest
     * append into a tamper finding. It must not: `session.end` lives in the log, and the log
     * is the thing whose completeness is in question.
     */
    @Test
    fun `finality is never inferred from a trailing session end in the log`() {
        setUpWorkspace()
        Files.write(
            slogPath,
            (sessionStartLine() + sessionStartLine(kind = "session.end")).toByteArray(Charsets.UTF_8),
        )
        val written = roll(isFinal = false) as RollingSealResult.Written
        assertFalse("a session.end in the log must not make a seal final", written.canonicalJson.contains("final"))
        assertNull(manifestJson()["final"])
    }

    // -----------------------------------------------------------------------
    // Failure is never fatal
    // -----------------------------------------------------------------------

    /**
     * `.provenance/` removed by a `git checkout`. The seal must fail as a VALUE and must NOT
     * recreate the directory: a resurrected directory holding a signed manifest that seals a
     * `.slog` which is not there is precisely the defect a reader exists to report.
     */
    @Test
    fun `a missing provenance directory yields WriteError and creates nothing`() {
        setUpWorkspace()
        wsRoot.resolve(".provenance").toFile().deleteRecursively()
        val result = roll()
        assertTrue("$result", result is RollingSealResult.WriteError)
        assertFalse(Files.exists(provDir))
    }

    /**
     * IJent answers an unimplemented filesystem operation with `kotlin.NotImplementedError` —
     * an Error, which an `Exception`-shaped handler lets straight out into the checkpoint
     * coroutine, killing the seal AND the checkpoint carrying it.
     */
    @Test
    fun `a write Error comes back as WriteError, never thrown`() {
        setUpWorkspace()
        val result = roll(writeFiles = { throw NotImplementedError("IJent cannot rename") })
        assertTrue("$result", result is RollingSealResult.WriteError)
    }

    /**
     * The ed25519 provider initialises lazily at the first sign(), so a broken provider raises
     * NoClassDefFoundError — same class of failure, same requirement.
     */
    @Test
    fun `a signing Error comes back as WriteError, never thrown`() {
        setUpWorkspace()
        val result = roll(signManifest = { _, _ -> throw NoClassDefFoundError("no ed25519 provider") })
        assertTrue("$result", result is RollingSealResult.WriteError)
    }

    /** An OutOfMemoryError reported as "seal write failed" is a wrong diagnosis. */
    @Test(expected = OutOfMemoryError::class)
    fun `a VirtualMachineError is not swallowed`() {
        setUpWorkspace()
        roll(writeFiles = { throw OutOfMemoryError("heap") })
    }

    private fun listing(): Set<String> =
        Files.list(provDir).use { s -> s.map { it.fileName.toString() }.toList() }.toSet()
}
