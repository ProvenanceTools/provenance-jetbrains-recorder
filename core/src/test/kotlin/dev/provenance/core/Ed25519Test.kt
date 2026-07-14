package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ed25519Test {
    @Test
    fun `round-trip sign then verify`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val msg = "hello provenance".toByteArray(Charsets.UTF_8)
        val sig = Ed25519.sign(msg, priv)
        assertEquals(64, sig.size)
        assertTrue(Ed25519.verify(sig, msg, pub))
    }

    @Test
    fun `tampered message fails verify`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val msg = "hello provenance".toByteArray(Charsets.UTF_8)
        val sig = Ed25519.sign(msg, priv)
        val tampered = "hello provenancE".toByteArray(Charsets.UTF_8)
        assertFalse(Ed25519.verify(sig, tampered, pub))
    }

    @Test
    fun `publicKeyOf derives the same public key as generation`() {
        val (priv, pub) = Ed25519.generateKeypair()
        assertEquals(Ed25519.bytesToHex(pub), Ed25519.bytesToHex(Ed25519.publicKeyOf(priv)))
    }

    @Test
    fun `verify returns false on malformed input rather than throwing`() {
        val (_, pub) = Ed25519.generateKeypair()
        val msg = "x".toByteArray(Charsets.UTF_8)
        // Wrong-length signature and wrong-length key must not throw.
        assertFalse(Ed25519.verify(ByteArray(10), msg, pub))
        assertFalse(Ed25519.verify(ByteArray(64), msg, ByteArray(3)))
    }

    @Test
    fun `hex round-trips lowercase`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte(), 0xab.toByte())
        val hex = Ed25519.bytesToHex(bytes)
        assertEquals("000f10ffab", hex)
        assertTrue(Ed25519.hexToBytes(hex).contentEquals(bytes))
    }

    @Test
    fun `cross-language vector matches noble ed25519 signature`() {
        val v = Json.parseToJsonElement(
            this::class.java.getResource("/conformance/ed25519.json")!!.readText(),
        ).jsonObject
        val priv = Ed25519.hexToBytes(v["priv_hex"]!!.jsonPrimitive.content)
        val expectedPub = v["pub_hex"]!!.jsonPrimitive.content
        val expectedSig = v["sig_hex"]!!.jsonPrimitive.content
        val msg = v["msg_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)

        // Deterministic RFC 8032: BouncyCastle must produce the identical signature @noble does.
        assertEquals(expectedSig, Ed25519.bytesToHex(Ed25519.sign(msg, priv)))
        assertEquals(expectedPub, Ed25519.bytesToHex(Ed25519.publicKeyOf(priv)))
        // And that signature verifies against the derived public key.
        assertTrue(Ed25519.verify(Ed25519.hexToBytes(expectedSig), msg, Ed25519.hexToBytes(expectedPub)))
    }
}
