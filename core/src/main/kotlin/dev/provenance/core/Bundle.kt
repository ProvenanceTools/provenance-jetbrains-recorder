package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bundle manifest model, on-the-wire shape emission, shape validation, and
 * ed25519 signing (recorder PRD §5.3). Mirrors log-core's bundle.ts + bundle-sign.ts.
 *
 * The signed payload is JCS(entire manifest object) → UTF-8 → ed25519 with the
 * session private key. The caller writes `canonicalJson` to manifest.json (exactly
 * what was signed) and `signatureHex` to manifest.sig. Never modify those after seal.
 */
data class SubmissionFileEntry(
    val path: String,
    /** "present" = bytes are in the bundle; "missing" = listed but absent at seal. */
    val status: String,
    /** Hex sha256 of the raw on-disk bytes; null iff status == "missing". */
    val sha256: String?,
)

data class SessionEntry(
    /** Session UUID from session.start; null when the .slog could not be parsed. */
    val sessionId: String?,
    val prevSessionId: String?,
    val slogSha256: String,
    val metaSha256: String,
)

data class BundleManifest(
    /** "1.0" = legacy (no submission_files); "1.1" carries final on-disk state. */
    val formatVersion: String,
    val assignmentId: String,
    val semester: String,
    val extensionHash: String,
    val sessions: List<SessionEntry>,
    /** Present on 1.1; null on legacy 1.0. */
    val submissionFiles: List<SubmissionFileEntry>?,
)

data class SignedBundleManifest(
    /** The exact JCS-canonical JSON written to manifest.json (and signed). */
    val canonicalJson: String,
    /** Hex ed25519 signature over the canonical JSON bytes (written to manifest.sig). */
    val signatureHex: String,
)

private val BUNDLE_HEX_64_RE = Regex("^[0-9a-f]{64}$")

/**
 * Emit the on-the-wire snake_case shape. `submission_files` is omitted when null (1.0).
 * Nullable fields (session/prev ids, missing sha256) are emitted as JSON null, not dropped —
 * JCS re-sorts keys, so field order here is irrelevant.
 */
fun BundleManifest.toJsonText(): String =
    buildJsonObject {
        put("format_version", formatVersion)
        put("assignment_id", assignmentId)
        put("semester", semester)
        put("extension_hash", extensionHash)
        put(
            "sessions",
            buildJsonArray {
                for (s in sessions) {
                    addJsonObject {
                        put("session_id", s.sessionId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("prev_session_id", s.prevSessionId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("slog_sha256", s.slogSha256)
                        put("meta_sha256", s.metaSha256)
                    }
                }
            },
        )
        if (submissionFiles != null) {
            put(
                "submission_files",
                buildJsonArray {
                    for (f in submissionFiles) {
                        addJsonObject {
                            put("path", f.path)
                            put("status", f.status)
                            put("sha256", f.sha256?.let { JsonPrimitive(it) } ?: JsonNull)
                        }
                    }
                },
            )
        }
    }.toString()

/**
 * Validate that a JSON text has the BundleManifest shape (mirrors log-core
 * validateBundleManifestShape). Accepts 1.0 without submission_files; 1.1 requires
 * it. Present files need a 64-hex sha; missing files need null sha.
 */
fun validateBundleManifestShape(jsonText: String): Result<BundleManifest> {
    val root =
        try {
            Json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("invalid_json: ${e.message}"))
        }
    val obj = root as? JsonObject
        ?: return Result.failure(IllegalArgumentException("not_object"))

    val version = (obj["format_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (version != "1.0" && version != "1.1") {
        return fail("wrong_version: ${obj["format_version"]}")
    }

    val assignmentId = obj.nonEmptyStr("assignment_id")
        ?: return fail("assignment_id must be a non-empty string")
    val semester = obj.nonEmptyStr("semester")
        ?: return fail("semester must be a non-empty string")

    val extensionHash = (obj["extension_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (extensionHash == null || !BUNDLE_HEX_64_RE.matches(extensionHash)) {
        return fail("extension_hash must be 64 lowercase hex chars")
    }

    val sessionsElem = obj["sessions"] as? JsonArray
        ?: return fail("sessions must be an array")
    val sessions = ArrayList<SessionEntry>(sessionsElem.size)
    for ((i, sElem) in sessionsElem.withIndex()) {
        val s = sElem as? JsonObject ?: return fail("sessions[$i] must be an object")

        val sessionId = s.nullableStr("session_id", requireNonEmpty = true) { return fail("sessions[$i].session_id invalid") }
        val prevSessionId = s.nullableStr("prev_session_id", requireNonEmpty = false) { return fail("sessions[$i].prev_session_id invalid") }

        val slog = (s["slog_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (slog == null || !BUNDLE_HEX_64_RE.matches(slog)) return fail("sessions[$i].slog_sha256 must be 64 hex chars")
        val meta = (s["meta_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (meta == null || !BUNDLE_HEX_64_RE.matches(meta)) return fail("sessions[$i].meta_sha256 must be 64 hex chars")

        sessions.add(SessionEntry(sessionId, prevSessionId, slog, meta))
    }

    var submissionFiles: List<SubmissionFileEntry>? = null
    if (version == "1.1") {
        val filesElem = obj["submission_files"] as? JsonArray
            ?: return fail("submission_files must be an array")
        val files = ArrayList<SubmissionFileEntry>(filesElem.size)
        for ((i, fElem) in filesElem.withIndex()) {
            val f = fElem as? JsonObject ?: return fail("submission_files[$i] must be an object")
            val path = f.nonEmptyStr("path") ?: return fail("submission_files[$i].path must be a non-empty string")
            val status = (f["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (status != "present" && status != "missing") return fail("submission_files[$i].status must be 'present' or 'missing'")
            val shaElem = f["sha256"]
            val sha: String?
            if (status == "present") {
                val s = (shaElem as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null || !BUNDLE_HEX_64_RE.matches(s)) return fail("submission_files[$i].sha256 must be a 64-hex string")
                sha = s
            } else {
                if (shaElem != null && shaElem != JsonNull) return fail("submission_files[$i].sha256 must be null for missing")
                sha = null
            }
            files.add(SubmissionFileEntry(path, status, sha))
        }
        submissionFiles = files
    }

    return Result.success(
        BundleManifest(version, assignmentId, semester, extensionHash, sessions, submissionFiles),
    )
}

/**
 * Canonicalize and ed25519-sign a bundle manifest with the session private key.
 * Returns both the canonical JSON (to persist as manifest.json) and the hex signature.
 */
fun signBundleManifest(manifest: BundleManifest, signingPrivkey32: ByteArray): SignedBundleManifest {
    val canonicalJson = Canonical.canonicalize(manifest.toJsonText())
    val sig = Ed25519.sign(canonicalJson.toByteArray(Charsets.UTF_8), signingPrivkey32)
    return SignedBundleManifest(canonicalJson, Ed25519.bytesToHex(sig))
}

private fun <T> fail(msg: String): Result<T> = Result.failure(IllegalArgumentException(msg))

private fun JsonObject.nonEmptyStr(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}

/** Returns the string value, or null if the field is JSON null. Calls [onInvalid] otherwise. */
private inline fun JsonObject.nullableStr(key: String, requireNonEmpty: Boolean, onInvalid: () -> Nothing): String? {
    val elem = this[key] ?: onInvalid()
    if (elem == JsonNull) return null
    val p = elem as? JsonPrimitive ?: onInvalid()
    if (!p.isString) onInvalid()
    if (requireNonEmpty && p.content.isEmpty()) onInvalid()
    return p.content
}
