package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pre-hash log entry: {seq, t, wall, kind, data}. Mirrors log-core's Envelope. */
data class Envelope(
    val seq: Long,
    val t: Long,
    val wall: String,
    val kind: String,
    val data: JsonObject,
)

/** Chained log entry: Envelope + prev_hash + hash. */
data class HashedEnvelope(
    val seq: Long,
    val t: Long,
    val wall: String,
    val kind: String,
    val data: JsonObject,
    val prevHash: String,
    val hash: String,
)

/** JSON text of the pre-hash entry. Field order is irrelevant — JCS re-sorts. */
fun Envelope.toJsonText(): String =
    buildJsonObject {
        put("seq", seq)
        put("t", t)
        put("wall", wall)
        put("kind", kind)
        put("data", data)
    }.toString()

/** JSON text of the chained entry, using on-the-wire snake_case hash keys. */
fun HashedEnvelope.toJsonText(): String =
    buildJsonObject {
        put("seq", seq)
        put("t", t)
        put("wall", wall)
        put("kind", kind)
        put("data", data)
        put("prev_hash", prevHash)
        put("hash", hash)
    }.toString()
