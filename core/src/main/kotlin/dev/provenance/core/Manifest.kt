package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Parser and ed25519 signature verifier for the `.provenance-manifest` assignment
 * file (recorder PRD §4.1) — the recorder activates only when this manifest is
 * present and verifies against the embedded course public key.
 *
 * Signed payload: JCS({assignment_id, semester, issued_at, files_under_review})
 * → UTF-8 → ed25519. The `sig` field is excluded before canonicalization.
 * Mirrors log-core's manifest.ts byte-for-byte.
 */
data class Manifest(
    val assignmentId: String,
    val semester: String,
    val issuedAt: String,
    val filesUnderReview: List<String>,
    /** Hex ed25519 signature, 128 chars (64 bytes). */
    val sig: String,
)

sealed interface ManifestParse {
    data class Ok(val manifest: Manifest) : ManifestParse

    data class Err(val reason: String) : ManifestParse
}

private val HEX_128_RE = Regex("^[0-9a-f]{128}$")
private val HEX_64_RE = Regex("^[0-9a-f]{64}$")

/**
 * Build the canonical UTF-8 bytes that were signed. The `sig` field is excluded —
 * only the four payload fields are canonicalized (JCS re-sorts them deterministically).
 */
private fun buildSignedPayload(m: Manifest): ByteArray {
    val payload = buildJsonObject {
        put("assignment_id", m.assignmentId)
        put("semester", m.semester)
        put("issued_at", m.issuedAt)
        putJsonArray("files_under_review") {
            for (f in m.filesUnderReview) add(JsonPrimitive(f))
        }
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Parse a `.provenance-manifest` file (text content) into a [Manifest].
 * Validates JSON structure and field shapes. Does NOT verify the signature.
 */
fun parseManifest(text: String): ManifestParse {
    val root =
        try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ManifestParse.Err("invalid_json: ${e.message}")
        }

    val obj = root as? JsonObject ?: return ManifestParse.Err("invalid_shape: must be an object")

    val assignmentId = obj.nonEmptyString("assignment_id")
        ?: return ManifestParse.Err("invalid_shape: assignment_id must be a non-empty string")
    val semester = obj.nonEmptyString("semester")
        ?: return ManifestParse.Err("invalid_shape: semester must be a non-empty string")
    val issuedAt = obj.nonEmptyString("issued_at")
        ?: return ManifestParse.Err("invalid_shape: issued_at must be a non-empty string")

    val filesElem = obj["files_under_review"]
    if (filesElem !is JsonArray) {
        return ManifestParse.Err("invalid_shape: files_under_review must be an array")
    }
    val files = ArrayList<String>(filesElem.size)
    for (f in filesElem) {
        val p = f as? JsonPrimitive
        if (p == null || !p.isString) {
            return ManifestParse.Err("invalid_shape: files_under_review elements must be strings")
        }
        files.add(p.content)
    }

    val sigPrim = obj["sig"] as? JsonPrimitive
    if (sigPrim == null || !sigPrim.isString) {
        return ManifestParse.Err("invalid_shape: sig missing")
    }
    if (!HEX_128_RE.matches(sigPrim.content)) {
        return ManifestParse.Err("invalid_shape: sig must be a 128-char hex string")
    }

    return ManifestParse.Ok(Manifest(assignmentId, semester, issuedAt, files, sigPrim.content))
}

/**
 * Verify the ed25519 signature on a parsed [Manifest] against the hex course
 * public key (32 bytes → 64 hex chars). Returns false (never throws) on any
 * malformed input.
 */
fun verifyManifest(manifest: Manifest, coursePubkeyHex: String): Boolean {
    if (!HEX_64_RE.matches(coursePubkeyHex)) return false
    if (!HEX_128_RE.matches(manifest.sig)) return false
    return try {
        val sigBytes = Ed25519.hexToBytes(manifest.sig)
        val pubBytes = Ed25519.hexToBytes(coursePubkeyHex)
        Ed25519.verify(sigBytes, buildSignedPayload(manifest), pubBytes)
    } catch (_: Exception) {
        false
    }
}

private fun JsonObject.nonEmptyString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}
