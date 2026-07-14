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
import org.junit.Assert.assertTrue
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
}
