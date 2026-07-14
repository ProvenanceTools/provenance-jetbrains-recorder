package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckpointTest {
    private val fixture by lazy {
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/checkpoint.json")!!.readText(),
        ).jsonObject
    }

    @Test
    fun `sign then verify true`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val cp = signCheckpoint(42, "cd".repeat(32), priv)
        assertTrue(verifyCheckpoint(cp, Ed25519.bytesToHex(pub)))
    }

    @Test
    fun `wrong pubkey verifies false`() {
        val (priv, _) = Ed25519.generateKeypair()
        val (_, otherPub) = Ed25519.generateKeypair()
        val cp = signCheckpoint(42, "cd".repeat(32), priv)
        assertFalse(verifyCheckpoint(cp, Ed25519.bytesToHex(otherPub)))
    }

    @Test
    fun `tampered hash verifies false`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val cp = signCheckpoint(42, "cd".repeat(32), priv)
        val tampered = cp.copy(hash = "ef".repeat(32))
        assertFalse(verifyCheckpoint(tampered, Ed25519.bytesToHex(pub)))
    }

    @Test
    fun `verify returns false on malformed input`() {
        val (_, pub) = Ed25519.generateKeypair()
        val cp = Checkpoint(1, "cd".repeat(32), "nothex")
        assertFalse(verifyCheckpoint(cp, Ed25519.bytesToHex(pub)))
    }

    @Test
    fun `cross-language signature matches log-core`() {
        val seq = fixture["seq"]!!.jsonPrimitive.long
        val hash = fixture["hash"]!!.jsonPrimitive.content
        val expectedSig = fixture["sig"]!!.jsonPrimitive.content
        val pub = fixture["session_pubkey_hex"]!!.jsonPrimitive.content
        // Deterministic: our sign must reproduce log-core's checkpoint signature.
        val priv = ByteArray(32) { 4 }
        val cp = signCheckpoint(seq, hash, priv)
        assertEquals(expectedSig, cp.sig)
        // And the log-core signature verifies with our verify.
        assertTrue(verifyCheckpoint(Checkpoint(seq, hash, expectedSig), pub))
    }
}
