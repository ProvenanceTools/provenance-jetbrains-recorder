package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val HEX_64 = Regex("^[0-9a-f]{64}$")

sealed interface ParseResult {
    data class Ok(val entries: List<HashedEnvelope>) : ParseResult
    data class Err(val line: Int, val message: String) : ParseResult
}

/** One NDJSON line: JCS-canonical JSON + newline. Mirrors log-core serializeEntry. */
fun serializeEntry(entry: HashedEnvelope): String =
    Canonical.canonicalize(entry.toJsonText()) + "\n"

/** Parse NDJSON text into HashedEnvelopes. Returns on the first error (1-indexed line). */
fun parseEntries(text: String): ParseResult {
    if (text == "") return ParseResult.Ok(emptyList())
    val out = ArrayList<HashedEnvelope>()
    val lines = text.split("\n")
    for (i in lines.indices) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val lineNumber = i + 1
        val obj = try {
            Json.parseToJsonElement(line) as? JsonObject
                ?: return ParseResult.Err(lineNumber, "not a JSON object")
        } catch (e: Exception) {
            return ParseResult.Err(lineNumber, "invalid JSON: ${e.message}")
        }
        val env = validateShape(obj) ?: return ParseResult.Err(lineNumber, "invalid shape")
        out.add(env)
    }
    return ParseResult.Ok(out)
}

private fun validateShape(obj: JsonObject): HashedEnvelope? {
    val seq = (obj["seq"]?.jsonPrimitive)?.long ?: return null
    val t = (obj["t"]?.jsonPrimitive)?.long ?: return null
    val wall = (obj["wall"]?.jsonPrimitive)?.contentOrNull ?: return null
    val kind = (obj["kind"]?.jsonPrimitive)?.contentOrNull ?: return null
    val data = obj["data"] as? JsonObject ?: return null
    val prevHash = (obj["prev_hash"]?.jsonPrimitive)?.contentOrNull ?: return null
    val hash = (obj["hash"]?.jsonPrimitive)?.contentOrNull ?: return null
    if (!HEX_64.matches(prevHash) || !HEX_64.matches(hash)) return null
    return HashedEnvelope(seq, t, wall, kind, data, prevHash, hash)
}
