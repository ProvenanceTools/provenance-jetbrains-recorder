package dev.provenance.core

import org.erdtman.jcs.JsonCanonicalizer

/**
 * RFC 8785 (JCS) canonical JSON — the JVM twin of log-core's `canonicalize`.
 * Deterministic key ordering, no insignificant whitespace, canonical numbers.
 * Do not hand-roll; this is the pinned contract surface.
 */
object Canonical {
    fun canonicalize(jsonText: String): String =
        try {
            JsonCanonicalizer(jsonText).encodedString
        } catch (e: Exception) {
            throw IllegalArgumentException("canonicalize: invalid JSON input", e)
        }
}
