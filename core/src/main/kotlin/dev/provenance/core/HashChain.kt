package dev.provenance.core

/** The ONE hash-chaining function (PRD §5.2). Mirrors log-core's chainEntry. */
const val GENESIS_PREV_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

fun chainEntry(prevHash: String, entry: Envelope): HashedEnvelope {
    val canonical = Canonical.canonicalize(entry.toJsonText())
    val hash = Sha256.hex(prevHash + canonical)
    return HashedEnvelope(
        seq = entry.seq,
        t = entry.t,
        wall = entry.wall,
        kind = entry.kind,
        data = entry.data,
        prevHash = prevHash,
        hash = hash,
    )
}
