package dev.provenance.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sign and verify per-checkpoint proofs in the session log (recorder PRD §4.6):
 * the chain of seq → hash checkpoints, signed every N events.
 *
 * Signed payload: JCS({hash: entryHash, seq}) → UTF-8 → ed25519. JCS sorts keys,
 * so the {hash, seq} ordering is normalized. Mirrors log-core's checkpoint-signer.ts.
 */
data class Checkpoint(
    val seq: Long,
    val hash: String,
    /** Hex-encoded ed25519 signature over JCS({hash, seq}). */
    val sig: String,
)

private fun checkpointBytes(seq: Long, entryHash: String): ByteArray {
    val json = buildJsonObject {
        put("hash", entryHash)
        put("seq", seq)
    }.toString()
    return Canonical.canonicalize(json).toByteArray(Charsets.UTF_8)
}

/** Sign a checkpoint (seq, entryHash) with the session private key. */
fun signCheckpoint(seq: Long, entryHash: String, privateKey32: ByteArray): Checkpoint {
    val sig = Ed25519.sign(checkpointBytes(seq, entryHash), privateKey32)
    return Checkpoint(seq, entryHash, Ed25519.bytesToHex(sig))
}

/**
 * Verify a checkpoint against the session public key (64 hex chars).
 * Returns false (never throws) for invalid signatures or malformed input.
 */
fun verifyCheckpoint(cp: Checkpoint, publicKeyHex: String): Boolean =
    try {
        val sigBytes = Ed25519.hexToBytes(cp.sig)
        val pubBytes = Ed25519.hexToBytes(publicKeyHex)
        Ed25519.verify(sigBytes, checkpointBytes(cp.seq, cp.hash), pubBytes)
    } catch (_: Exception) {
        false
    }
