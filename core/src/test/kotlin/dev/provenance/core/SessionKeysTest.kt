package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionKeysTest {
    private val fixture by lazy {
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/session-key.json")!!.readText(),
        ).jsonObject
    }

    private fun hex(key: String) = fixture[key]!!.jsonPrimitive.content

    @Test
    fun `generated keypair pubkey matches derived pubkey`() {
        val kp = generateSessionKeypair()
        assertEquals(kp.publicKeyHex, Ed25519.bytesToHex(Ed25519.publicKeyOf(kp.privateKey)))
        assertEquals(32, kp.privateKey.size)
    }

    @Test
    fun `round-trip encrypt then decrypt recovers original 32 bytes`() {
        val (priv, _) = Ed25519.generateKeypair()
        val manifestSig = "ab".repeat(64)
        val enc = encryptSessionPrivkey(priv, manifestSig)
        val dec = decryptSessionPrivkey(enc, manifestSig)
        assertTrue(dec.contentEquals(priv))
    }

    @Test
    fun `wrong manifestSig on decrypt throws`() {
        val (priv, _) = Ed25519.generateKeypair()
        val enc = encryptSessionPrivkey(priv, "ab".repeat(64))
        assertThrows(Exception::class.java) {
            decryptSessionPrivkey(enc, "cd".repeat(64))
        }
    }

    @Test
    fun `encrypted shape carries fixed algorithm and info`() {
        val enc = encryptSessionPrivkey(ByteArray(32) { 1 }, "ab".repeat(64))
        assertEquals("xchacha20-poly1305-hkdf-sha256-v1", enc.algorithm)
        assertEquals("provenance-session-key-v1", enc.info)
        assertEquals(48, enc.nonce.length) // 24 bytes
        assertEquals(96, enc.ciphertext.length) // 32 + 16 tag
        assertEquals(32, enc.salt.length) // 16 bytes
    }

    @Test
    fun `cross-language HKDF key matches noble`() {
        // Isolate the KDF: our HKDF-SHA256 must equal @noble/hashes' for the same inputs.
        val enc = encryptSessionPrivkey(
            Ed25519.hexToBytes(hex("privkey_hex")),
            hex("manifest_sig"),
            saltBytes = Ed25519.hexToBytes(hex("salt_hex")),
            nonceBytes = Ed25519.hexToBytes(hex("nonce_hex")),
        )
        // The ciphertext equality below already proves KDF+cipher jointly; this
        // asserts the deterministic ciphertext explicitly.
        assertEquals(hex("ciphertext_hex"), enc.ciphertext)
    }

    @Test
    fun `cross-language deterministic ciphertext matches noble byte-for-byte`() {
        val enc = encryptSessionPrivkey(
            Ed25519.hexToBytes(hex("privkey_hex")),
            hex("manifest_sig"),
            saltBytes = Ed25519.hexToBytes(hex("salt_hex")),
            nonceBytes = Ed25519.hexToBytes(hex("nonce_hex")),
        )
        assertEquals(hex("algorithm"), enc.algorithm)
        assertEquals(hex("nonce_hex"), enc.nonce)
        assertEquals(hex("salt_hex"), enc.salt)
        assertEquals(hex("info"), enc.info)
        // The gate: our XChaCha20-Poly1305 output equals @noble/ciphers' exactly.
        assertEquals(hex("ciphertext_hex"), enc.ciphertext)
    }

    @Test
    fun `cross-language decrypt of noble ciphertext recovers original`() {
        // Decrypt the @noble-produced ciphertext (from the fixture) with our decrypt.
        val enc = EncryptedPrivkey(
            algorithm = hex("algorithm"),
            nonce = hex("nonce_hex"),
            ciphertext = hex("ciphertext_hex"),
            salt = hex("salt_hex"),
            info = hex("info"),
        )
        val dec = decryptSessionPrivkey(enc, hex("manifest_sig"))
        assertEquals(hex("privkey_hex"), Ed25519.bytesToHex(dec))
    }
}
