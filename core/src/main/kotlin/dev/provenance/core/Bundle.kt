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
    /**
     * What this file was in the recording, not just in the bundle: "reviewed" or
     * "attachment". ABSENT READS AS "reviewed", which is what every 1.1/1.2 bundle
     * sealed before path scope existed means. Additive and optional for the same
     * reason `final` is: no version bump, and absence is never a finding.
     *
     * An "attachment" was sealed and hashed but never captured — it has no event
     * provenance by definition, so check 8 must not compare it against
     * reconstruction.
     *
     * Kotlin-side `null` means absent; emitted by [toJsonText] with the OMIT-WHEN-
     * ABSENT idiom (same as `final`), never as JSON `null` — an entry with no role
     * opinion must canonicalize identically to one from before this field existed.
     */
    val role: String? = null,
)

data class SessionEntry(
    /** Session UUID from session.start; null when the .slog could not be parsed. */
    val sessionId: String?,
    val prevSessionId: String?,
    val slogSha256: String,
    val metaSha256: String,
)

data class BundleManifest(
    /**
     * "1.0" = legacy (no submission_files); "1.1" carries final on-disk state;
     * "1.2" = a ROLLING seal, one file per session (see RollingManifest.kt).
     */
    val formatVersion: String,
    val assignmentId: String,
    val semester: String,
    val extensionHash: String,
    val sessions: List<SessionEntry>,
    /** Present on 1.1; null on legacy 1.0. */
    val submissionFiles: List<SubmissionFileEntry>?,
    /**
     * The 1.2 rolling seal's `final` marker: "this is the LAST seal this session will
     * ever get, so my digests commit to the WHOLE log, not to a prefix".
     *
     * Kotlin-side name is [isFinal]; the wire key is `final`. **Emitted only when true**
     * — see [toJsonText]. Always false for the classic 1.0/1.1 seal, which is sealed
     * once over a finished log and has no use for the distinction.
     */
    val isFinal: Boolean = false,
    /**
     * Whether the recorder's expected-content cap refused a path that the scope
     * put under review. Additive and optional. Absent means "this recorder does
     * not report", which is what every bundle sealed before path scope says, and
     * is not a finding.
     *
     * True is not an accusation either — it is the recorder disclosing that its
     * record of this session is incomplete, so a reader must NOT conclude
     * "in scope, no activity" about any file.
     *
     * Wire key is `scope_capped`, **emitted only when true** — see [toJsonText].
     * A non-capped session's manifest must stay byte-identical to what it
     * produced before this field existed, because the canonical bytes ARE the
     * signed message.
     */
    val scopeCapped: Boolean = false,
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
                            // OMITTED ENTIRELY when absent — never emitted as JSON null. Absence
                            // reads as "reviewed", the meaning every pre-path-scope entry already
                            // has, so an entry with no role opinion must canonicalize identically
                            // to one from before this field existed.
                            if (f.role != null) put("role", f.role)
                        }
                    }
                },
            )
        }
        // OMITTED ENTIRELY unless final — never emitted as `false`. The canonical bytes
        // ARE the signed message, and a non-final rolling manifest must stay byte-identical
        // to what 1.2 emitted before this field existed; those bytes are pinned by the
        // cross-language conformance vectors that three recorder implementations share.
        if (isFinal) put("final", true)
        // OMITTED ENTIRELY unless true — never emitted as `false`, for the same reason
        // `final` is: the canonical bytes ARE the signed message, and an uncapped
        // session's manifest must stay byte-identical to what it produced before this
        // field existed.
        if (scopeCapped) put("scope_capped", true)
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
            val roleElem = f["role"]
            val role = (roleElem as? JsonPrimitive)?.takeIf { it.isString }?.content
            // roleElem == null means the key is ABSENT (reads as "reviewed"), which is
            // fine. A PRESENT key — including explicit JSON null — must be one of the
            // two allowed strings; role is optional, not nullable.
            if (roleElem != null && role != "reviewed" && role != "attachment") {
                return fail("submission_files[$i].role must be 'reviewed' or 'attachment' when present")
            }
            files.add(SubmissionFileEntry(path, status, sha, role))
        }
        submissionFiles = files
    }

    // `scope_capped`. Optional everywhere; absence means "this recorder does not
    // report" (every bundle sealed before path scope), which is not a finding.
    // When present it must be a real boolean for the same reason `final` is.
    val scopeCappedElem = obj["scope_capped"]
    var scopeCapped = false
    if (scopeCappedElem != null) {
        val p = scopeCappedElem as? JsonPrimitive
        if (p == null || p.isString || (p.content != "true" && p.content != "false")) {
            return fail("scope_capped must be a boolean when present")
        }
        scopeCapped = p.content == "true"
    }

    return Result.success(
        BundleManifest(version, assignmentId, semester, extensionHash, sessions, submissionFiles, scopeCapped = scopeCapped),
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
