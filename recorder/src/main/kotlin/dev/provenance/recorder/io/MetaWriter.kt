package dev.provenance.recorder.io

import dev.provenance.core.Canonical
import dev.provenance.core.Checkpoint
import dev.provenance.core.EncryptedPrivkey
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * In-memory model of the .slog.meta file (PRD §4.6). Mirrors log-core's SlogMeta.
 * The meta file is NOT signed, but its bytes are canonicalized for stability.
 */
data class SlogMeta(
    val formatVersion: String = "1.0",
    val sessionId: String,
    val sessionPubkey: String,
    val encryptedSessionPrivkey: EncryptedPrivkey,
    val checkpoints: List<Checkpoint>,
) {
    fun toJsonText(): String = buildJsonObject {
        put("format_version", formatVersion)
        put("session_id", sessionId)
        put("session_pubkey", sessionPubkey)
        put(
            "encrypted_session_privkey",
            buildJsonObject {
                put("algorithm", encryptedSessionPrivkey.algorithm)
                put("nonce", encryptedSessionPrivkey.nonce)
                put("ciphertext", encryptedSessionPrivkey.ciphertext)
                put("salt", encryptedSessionPrivkey.salt)
                put("info", encryptedSessionPrivkey.info)
            },
        )
        put(
            "checkpoints",
            buildJsonArray {
                for (cp in checkpoints) {
                    add(
                        buildJsonObject {
                            put("seq", cp.seq)
                            put("hash", cp.hash)
                            put("sig", cp.sig)
                        },
                    )
                }
            },
        )
    }.toString()
}

/**
 * MetaWriter — owns the in-memory SlogMeta and atomic-writes the .slog.meta file.
 * Mirrors meta-writer.ts. Full-file rewrite per checkpoint (checkpoints are
 * infrequent, so this is not a hot path). Writes the initial (empty-checkpoints)
 * file immediately so the meta exists on disk from session start.
 */
class MetaWriter private constructor(
    private val metaPath: Path,
    private val checkpoints: MutableList<Checkpoint>,
    private val base: SlogMeta,
) {
    companion object {
        fun create(
            metaPath: Path,
            sessionId: String,
            sessionPubkeyHex: String,
            encryptedPrivkey: EncryptedPrivkey,
        ): MetaWriter {
            val base = SlogMeta(
                formatVersion = "1.0",
                sessionId = sessionId,
                sessionPubkey = sessionPubkeyHex,
                encryptedSessionPrivkey = encryptedPrivkey,
                checkpoints = emptyList(),
            )
            val writer = MetaWriter(metaPath, mutableListOf(), base)
            writer.write()
            return writer
        }
    }

    fun appendCheckpoint(cp: Checkpoint) {
        checkpoints.add(cp)
        write()
    }

    /** No-op, for symmetry with SessionWriter — the meta is already durable after each write. */
    fun dispose() {
        // no-op
    }

    private fun write() {
        val meta = base.copy(checkpoints = checkpoints.toList())
        atomicWriteFile(metaPath, Canonical.canonicalize(meta.toJsonText()))
    }
}
