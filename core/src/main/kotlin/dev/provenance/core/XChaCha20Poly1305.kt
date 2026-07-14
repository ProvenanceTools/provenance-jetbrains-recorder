package dev.provenance.core

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha) composed from primitives, matching
 * `@noble/ciphers`' construction byte-for-byte:
 *
 *   subkey  = HChaCha20(key32, nonce24[0:16])
 *   nonce12 = 0x00000000 || nonce24[16:24]
 *   out     = IETF-ChaCha20-Poly1305(subkey, nonce12, plaintext, aad = ∅)
 *
 * BouncyCastle ships no XChaCha20 AEAD, but its `ChaCha20Poly1305` mode is the
 * IETF (RFC 8439) construction with a 12-byte nonce and a 16-byte tag appended to
 * the ciphertext — exactly what the extended-nonce form reduces to. HChaCha20 is
 * implemented here (RFC/ draft appendix A) since BC does not expose it.
 */
internal object XChaCha20Poly1305 {
    // sigma = LE words of "expand 32-byte k".
    private const val S0 = 0x61707865
    private const val S1 = 0x3320646e
    private const val S2 = 0x79622d32
    private const val S3 = 0x6b206574

    fun encrypt(key32: ByteArray, nonce24: ByteArray, plaintext: ByteArray): ByteArray =
        run(true, key32, nonce24, plaintext)

    fun decrypt(key32: ByteArray, nonce24: ByteArray, ciphertextAndTag: ByteArray): ByteArray =
        run(false, key32, nonce24, ciphertextAndTag)

    private fun run(forEncryption: Boolean, key32: ByteArray, nonce24: ByteArray, input: ByteArray): ByteArray {
        require(key32.size == 32) { "key must be 32 bytes" }
        require(nonce24.size == 24) { "nonce must be 24 bytes" }

        val subkey = hChaCha20(key32, nonce24)
        val nonce12 = ByteArray(12)
        // First 4 bytes stay zero; last 8 = nonce24[16:24].
        System.arraycopy(nonce24, 16, nonce12, 4, 8)

        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(KeyParameter(subkey), 128, nonce12))
        val out = ByteArray(cipher.getOutputSize(input.size))
        var len = cipher.processBytes(input, 0, input.size, out, 0)
        len += cipher.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    /** HChaCha20: derive a 32-byte subkey from key32 and the first 16 bytes of the nonce. */
    private fun hChaCha20(key32: ByteArray, nonce24: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = S0; x[1] = S1; x[2] = S2; x[3] = S3
        for (i in 0 until 8) x[4 + i] = leWord(key32, i * 4)
        for (i in 0 until 4) x[12 + i] = leWord(nonce24, i * 4)

        repeat(10) {
            // column rounds
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            // diagonal rounds
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }

        // Subkey = words 0..3 and 12..15 (no state addition), little-endian.
        val out = ByteArray(32)
        val words = intArrayOf(x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15])
        for (i in words.indices) putLeWord(out, i * 4, words[i])
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun leWord(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLeWord(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v ushr 8) and 0xff).toByte()
        b[off + 2] = ((v ushr 16) and 0xff).toByte()
        b[off + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
