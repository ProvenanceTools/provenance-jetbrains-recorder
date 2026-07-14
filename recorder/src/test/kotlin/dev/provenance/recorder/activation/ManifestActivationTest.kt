package dev.provenance.recorder.activation

import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestActivationTest {

    /**
     * Hand-roll a signed manifest using core's Ed25519/Canonical primitives.
     * The signed payload is JCS({assignment_id, semester, issued_at, files_under_review}),
     * matching core's buildSignedPayload (the sig field is excluded before signing).
     */
    private fun signedManifestJson(assignmentId: String = "hw03", privkey: ByteArray): String {
        val payload = Canonical.canonicalize(
            """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"]}""",
        )
        val sig = Ed25519.bytesToHex(Ed25519.sign(payload.toByteArray(Charsets.UTF_8), privkey))
        return """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"],"sig":"$sig"}"""
    }

    @Test
    fun `valid signature yields Active with the parsed manifest`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
        assertEquals("hw03", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    @Test
    fun `wrong pubkey yields Inactive signature_invalid`() {
        val (priv, _) = Ed25519.generateKeypair()
        val (_, otherPub) = Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, Ed25519.bytesToHex(otherPub))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `malformed json yields Inactive parse_error, never throws`() {
        val result = evaluateManifestText("not json", "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("parse_error", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `well-formed but tampered field yields Inactive signature_invalid`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val text = signedManifestJson(assignmentId = "hw03", privkey = priv)
            .replace("\"hw03\"", "\"hw04\"") // tamper after signing
        val result = evaluateManifestText(text, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }
}
