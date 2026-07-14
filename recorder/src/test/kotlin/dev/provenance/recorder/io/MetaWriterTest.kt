package dev.provenance.recorder.io

import dev.provenance.core.Canonical
import dev.provenance.core.Checkpoint
import dev.provenance.core.EncryptedPrivkey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MetaWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun enc() = EncryptedPrivkey(
        algorithm = "xchacha20-poly1305-hkdf-sha256-v1",
        nonce = "aa".repeat(24),
        ciphertext = "bb".repeat(48),
        salt = "cc".repeat(16),
        info = "provenance-session-key-v1",
    )

    @Test
    fun `create writes canonical meta with empty checkpoints`() {
        val path = tmp.root.toPath().resolve("s.slog.meta")
        MetaWriter.create(path, "sess-1", "d".repeat(64), enc())
        val bytes = Files.readAllBytes(path)
        val obj = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        assertEquals("1.0", obj["format_version"]!!.jsonPrimitive.content)
        assertEquals("sess-1", obj["session_id"]!!.jsonPrimitive.content)
        assertEquals("d".repeat(64), obj["session_pubkey"]!!.jsonPrimitive.content)
        assertEquals(0, obj["checkpoints"]!!.jsonArray.size)
        assertEquals("xchacha20-poly1305-hkdf-sha256-v1", obj["encrypted_session_privkey"]!!.jsonObject["algorithm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `written bytes are canonicalized`() {
        val path = tmp.root.toPath().resolve("s.slog.meta")
        val w = MetaWriter.create(path, "sess-1", "d".repeat(64), enc())
        val meta = SlogMeta("1.0", "sess-1", "d".repeat(64), enc(), emptyList())
        assertEquals(Canonical.canonicalize(meta.toJsonText()), String(Files.readAllBytes(path), Charsets.UTF_8))
        w.dispose()
    }

    @Test
    fun `appendCheckpoint twice keeps both in order and no tmp files`() {
        val path = tmp.root.toPath().resolve("s.slog.meta")
        val w = MetaWriter.create(path, "sess-1", "d".repeat(64), enc())
        w.appendCheckpoint(Checkpoint(100, "e".repeat(64), "11".repeat(64)))
        w.appendCheckpoint(Checkpoint(200, "f".repeat(64), "22".repeat(64)))
        val obj = Json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
        val cps = obj["checkpoints"]!!.jsonArray
        assertEquals(2, cps.size)
        assertEquals(100L, cps[0].jsonObject["seq"]!!.jsonPrimitive.long)
        assertEquals(200L, cps[1].jsonObject["seq"]!!.jsonPrimitive.long)
        val siblings = Files.list(tmp.root.toPath()).use { it.toList() }
        assertTrue(siblings.none { it.fileName.toString().endsWith(".tmp") })
    }
}
