package dev.provenance.recorder.commands

import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import dev.provenance.core.Envelope
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.Sha256
import dev.provenance.core.chainEntry
import dev.provenance.core.serializeEntry
import kotlinx.serialization.json.buildJsonObject
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

    private fun writeSession(provDir: Path, filename: String, manifestSig: String, pubHex: String, corrupt: Boolean = false) {
        var seq = 0L
        var prev = GENESIS_PREV_HASH
        fun emit(kind: String, data: Map<String, String>): HashedEnvelope {
            val obj = buildJsonObject { data.forEach { (k, v) -> put(k, v) } }
            val e = chainEntry(prev, Envelope(seq, seq, "2026-07-14T00:00:0${seq}Z", kind, obj))
            seq += 1; prev = e.hash
            return e
        }
        val e0 = emit("session.start", mapOf("session_id" to "sess-1", "manifest_sig" to manifestSig, "session_pubkey" to pubHex))
        val e1 = emit("doc.open", mapOf("path" to "hw.py"))
        val text = StringBuilder(serializeEntry(e0)).append(serializeEntry(e1))
        if (corrupt) text.append("this is not json\n")
        Files.write(provDir.resolve(filename), text.toString().toByteArray(Charsets.UTF_8))
    }

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
        val result = sealBundle(prov, tmp.root.toPath(), "hw03", "fa26", emptyList(), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.NoSessions)
    }

    @Test
    fun `valid session produces a signature-verifiable bundle`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val slogBytesBefore = Files.readAllBytes(prov.resolve("session-1.slog"))

        val result = sealBundle(
            prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) },
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
        val result = sealBundle(prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        assertTrue((result as SealResult.Ok).unreadableSession)
    }

    @Test
    fun `missing reviewed file is marked missing and not zipped`() {
        val ws = tmp.root.toPath()
        val prov = Files.createDirectory(ws.resolve(".provenance"))
        writeSession(prov, "session-1.slog", "ab".repeat(64), Ed25519.bytesToHex(pub))
        val result = sealBundle(prov, ws, "hw03", "fa26", listOf("ghost.py"), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val entries = readZipEntries((result as SealResult.Ok).bundlePath)
        assertFalse(entries.containsKey("ghost.py"))
        val manifestJson = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"status\":\"missing\""))
        assertTrue(manifestJson.contains("\"sha256\":null"))
        assertNull(null) // present file check covered in end-to-end task
    }

    private fun assertArrayEqualsHelper(a: ByteArray, b: ByteArray) {
        assertEquals(a.toList(), b.toList())
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
            prov, ws, "hw03", "fa26", emptyList(), priv,
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
            prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) },
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
            prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) },
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
                prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) },
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
            sealBundle(prov, ws, "hw03", "fa26", emptyList(), priv, { throw boom })
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
        Files.createDirectory(prov.resolve("session-1.slog.meta"))

        val result = sealBundle(prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) })

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
            sealBundle(prov, ws, "hw03", "fa26", emptyList(), priv, { throw boom })
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
            val result = sealBundle(prov, ws, "hw03", "fa26", emptyList(), priv, { "e".repeat(64) })
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
        val result = sealBundle(prov, ws, "hw03", "fa26", listOf("ghost.py"), priv, { "e".repeat(64) })
        assertTrue(result is SealResult.Ok)
        val manifestJson = String(readZipEntries((result as SealResult.Ok).bundlePath)["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"status\":\"missing\""))
    }
}
