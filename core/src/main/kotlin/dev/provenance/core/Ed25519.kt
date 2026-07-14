package dev.provenance.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 (RFC 8032, pure variant, deterministic) via BouncyCastle.
 *
 * Because signing is deterministic, a fixed (private key, message) yields a fixed
 * 64-byte signature that is byte-identical to what `@noble/ed25519` produces. That
 * equality is the cross-language conformance gate (see Ed25519Test / ConformanceTest).
 *
 * Keys are raw 32-byte seeds (private) and 32-byte points (public), matching the
 * hex encodings log-core uses on the wire.
 */
object Ed25519 {
    private val secureRandom = SecureRandom()
    private val HEX = "0123456789abcdef".toCharArray()

    /** Generate a fresh keypair. Returns (privateKey32, publicKey32). */
    fun generateKeypair(): Pair<ByteArray, ByteArray> {
        val priv = Ed25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        return Pair(priv.encoded, pub.encoded)
    }

    /** Deterministic RFC 8032 signature over [message] with the 32-byte [privateKey32]. */
    fun sign(message: ByteArray, privateKey32: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(privateKey32, 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verify [signature] over [message] against the 32-byte [publicKey32].
     * Returns false (never throws) on any malformed input.
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey32: ByteArray): Boolean =
        try {
            val pub = Ed25519PublicKeyParameters(publicKey32, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pub)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }

    /** Derive the 32-byte public key from a 32-byte private key seed. */
    fun publicKeyOf(privateKey32: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateKey32, 0).generatePublicKey().encoded

    /** Lowercase hex encoding. */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Decode a lowercase/uppercase hex string to bytes. */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
