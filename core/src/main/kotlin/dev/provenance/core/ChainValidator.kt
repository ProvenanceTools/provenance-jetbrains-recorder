package dev.provenance.core

sealed interface ChainCheck {
    data object Valid : ChainCheck
    data class Broken(val seq: Long, val reason: String) : ChainCheck
}

private fun HashedEnvelope.asEnvelope(): Envelope = Envelope(seq, t, wall, kind, data)

/** Verify prev_hash linkage and recomputed hashes across the chain (PRD §5.2). */
fun validateChain(entries: List<HashedEnvelope>): ChainCheck {
    var expectedPrev = GENESIS_PREV_HASH
    for (entry in entries) {
        if (entry.prevHash != expectedPrev) {
            return ChainCheck.Broken(entry.seq, "prev_hash mismatch")
        }
        val recomputed = chainEntry(entry.prevHash, entry.asEnvelope()).hash
        if (recomputed != entry.hash) {
            return ChainCheck.Broken(entry.seq, "hash mismatch (tampered content)")
        }
        expectedPrev = entry.hash
    }
    return ChainCheck.Valid
}
