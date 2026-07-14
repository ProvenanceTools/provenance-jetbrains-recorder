package dev.provenance.core

import java.security.MessageDigest

/** SHA-256 → 64-char lowercase hex. Mirrors log-core's sha256Hex. */
object Sha256 {
    fun hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val sb = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    fun hex(input: String): String = hex(input.toByteArray(Charsets.UTF_8))
}
