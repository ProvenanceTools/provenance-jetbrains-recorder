package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundleTest {
    private val fixture by lazy {
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/bundle-manifest.json")!!.readText(),
        ).jsonObject
    }

    private fun fixtureManifest(): BundleManifest =
        BundleManifest(
            formatVersion = "1.1",
            assignmentId = "hw3",
            semester = "fa25",
            extensionHash = "aa".repeat(32),
            sessions = listOf(
                SessionEntry("11111111-1111-4111-8111-111111111111", null, "bb".repeat(32), "cc".repeat(32)),
                SessionEntry(
                    "22222222-2222-4222-8222-222222222222",
                    "11111111-1111-4111-8111-111111111111",
                    "dd".repeat(32),
                    "ee".repeat(32),
                ),
            ),
            submissionFiles = listOf(
                SubmissionFileEntry("src/main.py", "present", "ff".repeat(32)),
                SubmissionFileEntry("src/missing.py", "missing", null),
            ),
        )

    @Test
    fun `sign then verify with matching pubkey`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val signed = signBundleManifest(fixtureManifest(), priv)
        assertTrue(Ed25519.verify(Ed25519.hexToBytes(signed.signatureHex), signed.canonicalJson.toByteArray(), pub))
    }

    @Test
    fun `cross-language canonical json and signature match log-core`() {
        // A fixed session privkey (32 bytes of 0x03) — the fixture's session_pubkey.
        val priv = ByteArray(32) { 3 }
        val signed = signBundleManifest(fixtureManifest(), priv)
        assertEquals(fixture["canonical_json"]!!.jsonPrimitive.content, signed.canonicalJson)
        assertEquals(fixture["signature_hex"]!!.jsonPrimitive.content, signed.signatureHex)
        // And the log-core-produced signature verifies against the fixture pubkey.
        val pub = Ed25519.hexToBytes(fixture["session_pubkey_hex"]!!.jsonPrimitive.content)
        assertTrue(Ed25519.verify(Ed25519.hexToBytes(signed.signatureHex), signed.canonicalJson.toByteArray(), pub))
    }

    @Test
    fun `shape validator accepts a real 1_1 manifest`() {
        val text = fixture["manifest"]!!.jsonObject.toString()
        val r = validateBundleManifestShape(text)
        assertTrue(r.isSuccess)
        val m = r.getOrThrow()
        assertEquals("1.1", m.formatVersion)
        assertEquals(2, m.sessions.size)
        val files = m.submissionFiles!!
        assertEquals(2, files.size)
        assertEquals(null, files[1].sha256)
        assertEquals(null, m.sessions[0].prevSessionId)
    }

    @Test
    fun `shape validator accepts 1_0 without submission_files`() {
        val text = """
            {"format_version":"1.0","assignment_id":"hw3","semester":"fa25",
             "extension_hash":"${"aa".repeat(32)}",
             "sessions":[{"session_id":"s","prev_session_id":null,"slog_sha256":"${"bb".repeat(32)}","meta_sha256":"${"cc".repeat(32)}"}]}
        """.trimIndent()
        val r = validateBundleManifestShape(text)
        assertTrue(r.isSuccess)
        assertEquals(null, r.getOrThrow().submissionFiles)
    }

    @Test
    fun `shape validator rejects wrong version`() {
        val text = fixture["manifest"]!!.jsonObject.toString().replace("\"1.1\"", "\"2.0\"")
        assertTrue(validateBundleManifestShape(text).isFailure)
    }

    @Test
    fun `shape validator rejects missing extension_hash`() {
        val text = """
            {"format_version":"1.0","assignment_id":"hw3","semester":"fa25","sessions":[]}
        """.trimIndent()
        assertTrue(validateBundleManifestShape(text).isFailure)
    }

    @Test
    fun `shape validator rejects non-64-hex sha`() {
        val text = """
            {"format_version":"1.0","assignment_id":"hw3","semester":"fa25",
             "extension_hash":"${"aa".repeat(32)}",
             "sessions":[{"session_id":"s","prev_session_id":null,"slog_sha256":"abcd","meta_sha256":"${"cc".repeat(32)}"}]}
        """.trimIndent()
        assertTrue(validateBundleManifestShape(text).isFailure)
    }

    @Test
    fun `shape validator rejects 1_1 missing submission_files`() {
        val text = """
            {"format_version":"1.1","assignment_id":"hw3","semester":"fa25",
             "extension_hash":"${"aa".repeat(32)}","sessions":[]}
        """.trimIndent()
        assertTrue(validateBundleManifestShape(text).isFailure)
    }

    @Test
    fun `shape validator rejects present file with null sha`() {
        val text = """
            {"format_version":"1.1","assignment_id":"hw3","semester":"fa25",
             "extension_hash":"${"aa".repeat(32)}","sessions":[],
             "submission_files":[{"path":"a.py","status":"present","sha256":null}]}
        """.trimIndent()
        assertTrue(validateBundleManifestShape(text).isFailure)
    }

    @Test
    fun `toJsonText omits submission_files for 1_0`() {
        val m = BundleManifest("1.0", "hw3", "fa25", "aa".repeat(32), emptyList(), null)
        assertFalse(m.toJsonText().contains("submission_files"))
    }
}
