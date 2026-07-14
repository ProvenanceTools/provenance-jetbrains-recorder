package dev.provenance.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom

/**
 * Per-session ephemeral ed25519 keypair + private-key encryption (recorder PRD §4.6).
 *
 * The private key is encrypted with a key derived from the assignment manifest's
 * signature, so it cannot be recovered without the manifest (replay resistance).
 * Mirrors log-core's session-keys.ts byte-for-byte:
 * - KDF: HKDF-SHA256, IKM = hex-decoded manifest sig, salt = 16 random bytes,
 *   info = ASCII "provenance-session-key-v1", output = 32 bytes.
 * - Cipher: XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha), 24-byte nonce, 16-byte
 *   Poly1305 tag appended to the ciphertext, empty AAD.
 *
 * BouncyCastle has no XChaCha20 AEAD, so XChaCha20-Poly1305 is composed the same
 * way @noble/ciphers does: derive a subkey via HChaCha20(key, nonce[0:16]), then
 * run IETF ChaCha20-Poly1305 (RFC 8439, BC's ChaCha20Poly1305 mode) with the
 * subkey and a 12-byte nonce of 4 zero bytes || nonce[16:24].
 */
data class SessionKeypair(
    /** Hex-encoded ed25519 public key (32 bytes → 64 hex chars). */
    val publicKeyHex: String,
    /** Raw 32-byte ed25519 secret key (in memory only; never persisted raw). */
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SessionKeypair && publicKeyHex == other.publicKeyHex && privateKey.contentEquals(other.privateKey))

    override fun hashCode(): Int = 31 * publicKeyHex.hashCode() + privateKey.contentHashCode()
}

data class EncryptedPrivkey(
    val algorithm: String,
    /** Hex-encoded XChaCha20 nonce (24 bytes → 48 hex chars). */
    val nonce: String,
    /** Hex-encoded ciphertext (32 bytes plaintext + 16 bytes tag → 96 hex chars). */
    val ciphertext: String,
    /** Hex-encoded HKDF salt (16 bytes → 32 hex chars). */
    val salt: String,
    /** ASCII info string passed to HKDF. Fixed: "provenance-session-key-v1". */
    val info: String,
)

private const val HKDF_INFO = "provenance-session-key-v1"
private const val ALGORITHM = "xchacha20-poly1305-hkdf-sha256-v1"

private val sessionRandom = SecureRandom()

/** Generate a fresh ed25519 keypair for this session. */
fun generateSessionKeypair(): SessionKeypair {
    val (priv, pub) = Ed25519.generateKeypair()
    return SessionKeypair(Ed25519.bytesToHex(pub), priv)
}

/** HKDF-SHA256: IKM = hex-decoded manifestSig, salt, info = fixed ASCII, len 32. */
private fun deriveKey(manifestSig: String, saltBytes: ByteArray): ByteArray {
    val ikm = Ed25519.hexToBytes(manifestSig)
    val info = HKDF_INFO.toByteArray(Charsets.US_ASCII)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(ikm, saltBytes, info))
    val out = ByteArray(32)
    hkdf.generateBytes(out, 0, 32)
    return out
}

/**
 * Encrypt the 32-byte private key under a key derived from [manifestSig].
 * salt/nonce are injectable for deterministic tests; default to fresh randomness.
 */
fun encryptSessionPrivkey(
    privateKey32: ByteArray,
    manifestSig: String,
    saltBytes: ByteArray = ByteArray(16).also { sessionRandom.nextBytes(it) },
    nonceBytes: ByteArray = ByteArray(24).also { sessionRandom.nextBytes(it) },
): EncryptedPrivkey {
    require(saltBytes.size == 16) { "salt must be 16 bytes" }
    require(nonceBytes.size == 24) { "nonce must be 24 bytes" }
    val symmetricKey = deriveKey(manifestSig, saltBytes)
    val ciphertext = XChaCha20Poly1305.encrypt(symmetricKey, nonceBytes, privateKey32)
    return EncryptedPrivkey(
        algorithm = ALGORITHM,
        nonce = Ed25519.bytesToHex(nonceBytes),
        ciphertext = Ed25519.bytesToHex(ciphertext),
        salt = Ed25519.bytesToHex(saltBytes),
        info = HKDF_INFO,
    )
}

/**
 * Decrypt the private key. Throws (auth-tag failure) if [manifestSig] is wrong —
 * the security property that makes a wrong manifest sig unable to recover the key.
 */
fun decryptSessionPrivkey(enc: EncryptedPrivkey, manifestSig: String): ByteArray {
    val saltBytes = Ed25519.hexToBytes(enc.salt)
    val nonceBytes = Ed25519.hexToBytes(enc.nonce)
    val ciphertext = Ed25519.hexToBytes(enc.ciphertext)
    val symmetricKey = deriveKey(manifestSig, saltBytes)
    return XChaCha20Poly1305.decrypt(symmetricKey, nonceBytes, ciphertext)
}
