package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Parser and ed25519 signature verifier for the `.provenance-manifest` assignment
 * file (recorder PRD §4.1) — the recorder activates only when this manifest is
 * present and its signature verifies.
 *
 * Two format versions live here; mirrors log-core's `manifest.ts` byte-for-byte.
 *
 * **1.x** (PRD §4.1) has no `format_version` field at all, so 1.x manifests are
 * identified by its *absence*. Signed payload:
 *
 *   JCS({assignment_id, semester, issued_at, files_under_review})
 *
 * **2.0** (program spec §3) carries two independent signature scopes in one file:
 * a course-signed payload and a root-signed `course_cert` that authorizes the
 * course key. Signed payload:
 *
 *   JCS({format_version, course_id, assignment_id, semester, issued_at,
 *        files_under_review, collaboration, submission, scope, policy})
 *
 * `buildSignedPayload` excludes `sig` in both versions, and excludes `course_cert`
 * in 2.0 — the course does not sign its own certificate.
 *
 * **1.x parsing is supported permanently.** Archived submissions must still
 * validate years later; that adjudication case is precisely what justifies the
 * whole program (program spec §9). A missing `format_version` defaults to `"1.0"`
 * and parses successfully — it never rejects.
 *
 * ## Permanent constraint: no user-derived object keys
 *
 * Every key in the signed payload is a fixed ASCII identifier chosen by us, and
 * every future addition must be too — never a course id, assignment id, path, or
 * any other user-supplied string promoted to a key. See `CourseCert.kt` for the
 * full statement of the rule; `policy` is the one signed field with open-ended
 * contents, so it is the one place the rule has to be *enforced* rather than just
 * observed.
 */
data class Manifest(
    val assignmentId: String,
    val semester: String,
    val issuedAt: String,
    val filesUnderReview: List<String>,
    /** Hex ed25519 signature, 128 chars (64 bytes). */
    val sig: String,
    /**
     * `"1.0"` (or null, meaning 1.0) or `"2.0"`.
     *
     * Nullable so every existing 1.x construction site stays valid; [parseManifest]
     * always populates it with the resolved value, so a parsed manifest never
     * leaves you guessing. Use [manifestFormatVersion] when reading a hand-built
     * one.
     *
     * The 2.0 fields below are nullable for the same reason: 1.x manifests must
     * stay constructible. [parseManifest] requires them all when `format_version`
     * is `"2.0"`.
     *
     * These trail `sig` rather than leading the list, as they do in log-core,
     * because Kotlin construction is positional and every existing 1.x call site
     * passes the five legacy fields in order.
     */
    val formatVersion: String? = null,
    /** MUST equal `course_cert.course_id` — see [verifyManifestChain] step 3. */
    val courseId: String? = null,
    val collaboration: ManifestCollaboration? = null,
    val submission: ManifestSubmission? = null,
    val scope: ManifestScope? = null,
    /**
     * Capture policy. Inside the signed payload so a professor can turn capture
     * down and a student cannot turn it off (program spec §4).
     *
     * Canonicalized verbatim, unknown keys included, so the block round-trips
     * byte-for-byte through signature verification. Resolve it with
     * [resolveCapturePolicy].
     */
    val policy: JsonObject? = null,
    /**
     * Root-signed certificate authorizing the course key. Travels inline but sits
     * OUTSIDE the course-signed payload.
     */
    val courseCert: CourseCert? = null,
)

/** Whether the assignment is worked on alone or by a group (program spec §3). */
enum class ManifestCollaboration(val wire: String) {
    SOLO("solo"),
    GROUP("group"),
    ;

    companion object {
        fun fromWire(value: String): ManifestCollaboration? = entries.firstOrNull { it.wire == value }
    }
}

/** How the work reaches the analyzer: a sealed bundle, or a git repo. */
enum class ManifestSubmission(val wire: String) {
    BUNDLE("bundle"),
    GIT("git"),
    ;

    companion object {
        fun fromWire(value: String): ManifestSubmission? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether the assignment scope is one directory or a whole repository. */
enum class ManifestScope(val wire: String) {
    DIRECTORY("directory"),
    REPO("repo"),
    ;

    companion object {
        fun fromWire(value: String): ManifestScope? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface ManifestParse {
    data class Ok(val manifest: Manifest) : ManifestParse

    data class Err(val reason: String) : ManifestParse
}

/**
 * Outcome of [verifyManifestChain]. Note that an out-of-window `issued_at` is NOT
 * a failure here — it is non-fatal and is reported on [Ok.window] instead.
 */
sealed interface ManifestChain {
    data class Ok(
        /** The course this manifest is proven to belong to (manifest and cert agree). */
        val courseId: String,
        /** The course public key the root vouched for. */
        val coursePubkey: String,
        val cert: CourseCert,
        /**
         * Step 4, non-fatal. Out of window does NOT invalidate anything — the
         * caller decides. Program spec §4: an expired cert must not stop a
         * recorder from recording, because silently halting capture for a whole
         * class is a worse failure for an integrity tool than recording under a
         * stale key.
         */
        val window: CertWindowStatus,
    ) : ManifestChain

    /** Every way the chain walk can fail. [kind] is the wire name log-core uses. */
    sealed interface Err : ManifestChain {
        val kind: String
    }

    /**
     * Step 0: the manifest is not 2.0, so there is no trust chain to walk.
     *
     * This gate is a security control, not a formality. At 1.x, `course_id`,
     * `collaboration`, `submission`, `scope`, and `policy` are NOT in the signed
     * payload. Without this check a student holding any legitimately-issued 1.x
     * manifest from their own course could staple on that course's (public,
     * root-signed, freely copyable) certificate, add a matching `course_id` to
     * satisfy step 3, and staple on an arbitrary unsigned `policy` — and the whole
     * chain would return ok. That would hand the student the capture off switch,
     * which is exactly what putting `policy` inside the signed payload exists to
     * prevent. 1.x manifests take the legacy [verifyManifest] path and have no
     * policy.
     */
    data class NotManifest20(val formatVersion: String) : Err {
        override val kind: String get() = "not_manifest_2_0"
    }

    data object MissingCourseCert : Err {
        override val kind: String get() = "missing_course_cert"
    }

    /**
     * Step 0b: the manifest does not satisfy the 2.0 shape. Reported before any
     * signature work, because a 2.0 manifest with a required field missing
     * canonicalizes to a payload without that key — so an unvalidated object could
     * chain successfully while carrying no `policy` at all.
     */
    data class InvalidManifestShape(val reason: String) : Err {
        override val kind: String get() = "invalid_manifest_shape"
    }

    /** Step 1: `course_cert` does not verify against the root public key. */
    data object InvalidRootSignature : Err {
        override val kind: String get() = "invalid_root_signature"
    }

    /** Step 2: the payload does not verify against `course_cert.course_pubkey`. */
    data object InvalidCourseSignature : Err {
        override val kind: String get() = "invalid_course_signature"
    }

    /** Step 3: 61B's key signing a manifest that claims to be 61C. */
    data class CourseIdMismatch(
        val manifestCourseId: String?,
        val certCourseId: String,
    ) : Err {
        override val kind: String get() = "course_id_mismatch"
    }
}

private val HEX_128_RE = Regex("^[0-9a-f]{128}$")
private val HEX_64_RE = Regex("^[0-9a-f]{64}$")

/** Printable ASCII. See `validateSignedSubtree`. */
private val ASCII_KEY_RE = Regex("^[\\x20-\\x7e]+$")

/** The format version of a manifest with no `format_version` field. */
const val MANIFEST_FORMAT_VERSION_LEGACY: String = "1.0"

/** The version at which the trust chain, policy, and capability flags appear. */
const val MANIFEST_FORMAT_VERSION_2: String = "2.0"

/**
 * Resolve a manifest's format version.
 *
 * **An absent `format_version` means `"1.0"`.** 1.x manifests have no such field;
 * treating its absence as anything but 1.0 would break every archived submission.
 * Non-negotiable (program spec §3).
 */
fun manifestFormatVersion(manifest: Manifest): String =
    manifest.formatVersion ?: MANIFEST_FORMAT_VERSION_LEGACY

/**
 * Build the canonical UTF-8 bytes that were signed.
 *
 * `sig` is always excluded. In 2.0, `course_cert` is excluded too — the course
 * does not sign its own certificate.
 *
 * For a 1.x manifest this produces **exactly** the bytes it produced before
 * Manifest 2.0 existed: the same four fields, no `format_version`. Existing
 * signatures on archived manifests therefore keep verifying forever.
 *
 * JCS re-sorts keys, so the literal order below has no effect on the output.
 *
 * `internal` rather than private so the conformance suite can assert the exact
 * canonical bytes against the `canonical_json` field the vectors export. That
 * string comparison is the sharpest cross-language check there is — a signature
 * mismatch tells you something is wrong, the canonical bytes tell you what.
 */
internal fun buildSignedPayload(m: Manifest): ByteArray {
    val payload = if (manifestFormatVersion(m) == MANIFEST_FORMAT_VERSION_2) {
        buildJsonObject {
            put("format_version", MANIFEST_FORMAT_VERSION_2)
            // A null field is omitted rather than emitted as JSON null, matching
            // `canonicalize`'s treatment of an `undefined` value in log-core. Such
            // a manifest is rejected by validateManifestShape before it reaches a
            // trusted verdict; the omission only has to be *identical* across ports.
            m.courseId?.let { put("course_id", it) }
            put("assignment_id", m.assignmentId)
            put("semester", m.semester)
            put("issued_at", m.issuedAt)
            putJsonArray("files_under_review") {
                for (f in m.filesUnderReview) add(JsonPrimitive(f))
            }
            m.collaboration?.let { put("collaboration", it.wire) }
            m.submission?.let { put("submission", it.wire) }
            m.scope?.let { put("scope", it.wire) }
            m.policy?.let { put("policy", it) }
        }.toString()
    } else {
        buildJsonObject {
            put("assignment_id", m.assignmentId)
            put("semester", m.semester)
            put("issued_at", m.issuedAt)
            putJsonArray("files_under_review") {
                for (f in m.filesUnderReview) add(JsonPrimitive(f))
            }
        }.toString()
    }
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Walk an attacker-influenced sub-tree destined for the signed payload, rejecting
 * anything that would make the signed bytes disagree with what the rest of the
 * system reads. `policy` is the only signed field with open-ended contents, so it
 * is the only place these hazards can enter.
 *
 *  - **Printable-ASCII keys.** The permanent constraint documented in
 *    `CourseCert.kt`: provnvim's hand-rolled Lua JCS sorts object keys bytewise
 *    while JS and Kotlin sort by UTF-16 code unit, and the two orderings diverge
 *    above U+007F. The same manifest would then verify on one recorder and fail on
 *    another.
 *  - **Finite numbers.** A hand-built `JsonPrimitive(Double.NaN)` renders as bare
 *    `NaN`, which the canonicalizer rejects by throwing; rejecting here turns that
 *    into a value rather than an exception.
 *
 * log-core additionally *rebuilds* the sub-tree to strip `toJSON` methods and
 * accessor properties, which JS `canonicalize` would honour and every later reader
 * would not. `JsonObject` is plain immutable data with no such hooks, so this port
 * validates in place instead of rebuilding. Key ORDER is irrelevant either way:
 * JCS sorts keys.
 *
 * Returns an error reason, or null when the sub-tree is acceptable.
 */
private fun validateSignedSubtree(value: JsonElement, path: String): String? {
    when (value) {
        is JsonObject -> {
            for ((key, child) in value) {
                if (!ASCII_KEY_RE.matches(key)) {
                    return "invalid_shape: $path object keys must be printable ASCII"
                }
                validateSignedSubtree(child, "$path.$key")?.let { return it }
            }
            return null
        }

        is JsonArray -> {
            for ((i, child) in value.withIndex()) {
                validateSignedSubtree(child, "$path[$i]")?.let { return it }
            }
            return null
        }

        is JsonPrimitive -> {
            if (value.isString) return null
            when (value.content) {
                "true", "false", "null" -> return null
            }
            val d = value.content.toDoubleOrNull()
            if (d == null || !d.isFinite()) {
                return "invalid_shape: $path must be a finite number"
            }
            return null
        }
    }
}

/**
 * The invariants a [Manifest] must satisfy, checked on the data class itself so a
 * single implementation serves both [parseManifestValue] and [verifyManifestChain].
 *
 * Returns an error reason, or null when the manifest is well-formed.
 */
private fun validateManifestShape(m: Manifest): String? {
    val formatVersion = manifestFormatVersion(m)
    val isV2 = formatVersion == MANIFEST_FORMAT_VERSION_2
    if (!isV2 && !formatVersion.startsWith("1.")) {
        return "invalid_shape: unsupported format_version '$formatVersion'"
    }

    if (m.assignmentId.isEmpty()) return "invalid_shape: assignment_id must be a non-empty string"
    if (m.semester.isEmpty()) return "invalid_shape: semester must be a non-empty string"
    if (m.issuedAt.isEmpty()) return "invalid_shape: issued_at must be a non-empty string"
    if (!HEX_128_RE.matches(m.sig)) return "invalid_shape: sig must be a 128-char hex string"

    if (!isV2) return null

    // --- 2.0-only fields, all required. A fixed key set means the Kotlin and Lua
    // --- ports canonicalize without needing a "which optionals were present"
    // --- rule, which would be a divergence risk across three implementations.
    if (m.courseId.isNullOrEmpty()) return "invalid_shape: course_id must be a non-empty string"
    if (m.collaboration == null) return "invalid_shape: collaboration must be one of solo | group"
    if (m.submission == null) return "invalid_shape: submission must be one of bundle | git"
    if (m.scope == null) return "invalid_shape: scope must be one of directory | repo"
    val policy = m.policy ?: return "invalid_shape: policy must be an object"
    validateSignedSubtree(policy, "policy")?.let { return it }
    if (m.courseCert == null) return "invalid_shape: course_cert missing"

    return null
}

/**
 * Parse a `.provenance-manifest` file (text content) into a [Manifest].
 * Validates JSON structure and field shapes. Does NOT verify any signature.
 *
 * Version handling (program spec §3):
 *
 *  - **No `format_version`** → defaults to `"1.0"` and parses successfully. That
 *    is how every 1.x manifest ever written is identified, so rejecting it would
 *    break every archived submission.
 *  - **`"2.0"`** → the 2.0 fields are all required, including `course_cert`.
 *  - **Any other `"1.x"`** → parsed with the 1.x rules.
 *  - **Anything else** → rejected rather than guessed at. Silently canonicalizing
 *    an unknown version under the wrong rules would produce a signature failure
 *    with a misleading cause.
 *
 * Unknown top-level keys are ignored for forward compatibility. That is safe
 * because canonicalization operates on the named fields only, so an unknown key
 * cannot silently change the signed bytes.
 */
fun parseManifest(text: String): ManifestParse {
    val root =
        try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ManifestParse.Err("invalid_json: ${e.message}")
        }
    return parseManifestValue(root)
}

/**
 * The same validation as [parseManifest], on an already-deserialized value.
 *
 * Needed because a manifest does not only arrive as file text any more: program
 * spec §5 puts the full manifest inside `session.start`, so a consumer receives
 * one as a parsed object embedded in a larger payload. Without an object-taking
 * validator those consumers would skip validation entirely and hand an unchecked
 * object to [verifyManifestChain], which is exactly how "the chain verified" stops
 * implying "the course signed a policy".
 */
fun parseManifestValue(value: JsonElement?): ManifestParse {
    val obj = value as? JsonObject ?: return ManifestParse.Err("invalid_shape: must be an object")

    // format_version: absent means 1.0. Never a rejection reason on its own.
    val fvElem = obj["format_version"]
    if (fvElem != null && (fvElem !is JsonPrimitive || !fvElem.isString)) {
        return ManifestParse.Err("invalid_shape: format_version must be a string when present")
    }
    // Smart-cast: past the guard, fvElem is either null or a string JsonPrimitive.
    val formatVersion = fvElem?.content ?: MANIFEST_FORMAT_VERSION_LEGACY
    val isV2 = formatVersion == MANIFEST_FORMAT_VERSION_2
    if (!isV2 && !formatVersion.startsWith("1.")) {
        return ManifestParse.Err("invalid_shape: unsupported format_version '$formatVersion'")
    }

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

    val base = Manifest(
        assignmentId = assignmentId,
        semester = semester,
        issuedAt = issuedAt,
        filesUnderReview = files,
        sig = sigPrim.content,
        formatVersion = formatVersion,
    )
    if (!isV2) return ManifestParse.Ok(base)

    // --- 2.0-only fields, all required --------------------------------------

    val courseId = obj.nonEmptyString("course_id")
        ?: return ManifestParse.Err("invalid_shape: course_id must be a non-empty string")

    val collaboration = obj.nonEmptyString("collaboration")?.let { ManifestCollaboration.fromWire(it) }
        ?: return ManifestParse.Err("invalid_shape: collaboration must be one of solo | group")
    val submission = obj.nonEmptyString("submission")?.let { ManifestSubmission.fromWire(it) }
        ?: return ManifestParse.Err("invalid_shape: submission must be one of bundle | git")
    val scope = obj.nonEmptyString("scope")?.let { ManifestScope.fromWire(it) }
        ?: return ManifestParse.Err("invalid_shape: scope must be one of directory | repo")

    val policy = obj["policy"] as? JsonObject
        ?: return ManifestParse.Err("invalid_shape: policy must be an object")

    if (obj["course_cert"] == null) {
        return ManifestParse.Err("invalid_shape: course_cert missing")
    }
    val cert = when (val parsed = parseCourseCert(obj["course_cert"])) {
        is CourseCertParse.Err ->
            return ManifestParse.Err("invalid_shape: course_cert: ${parsed.reason.removePrefix("invalid_shape: ")}")

        is CourseCertParse.Ok -> parsed.cert
    }

    val manifest = base.copy(
        courseId = courseId,
        collaboration = collaboration,
        submission = submission,
        scope = scope,
        policy = policy,
        courseCert = cert,
    )
    validateManifestShape(manifest)?.let { return ManifestParse.Err(it) }
    return ManifestParse.Ok(manifest)
}

/**
 * Sign an assignment manifest with a signing private key (the inverse of
 * [verifyManifest]). Returns the hex ed25519 signature over the version-appropriate
 * signed payload.
 *
 * Signing a 2.0 manifest with a 2.0 field missing produces a signature over a
 * payload with that key absent, which will then fail verification against a
 * correctly-parsed manifest. Build 2.0 manifests through a complete object.
 */
fun signManifest(manifest: Manifest, signingPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildSignedPayload(manifest), signingPrivkey32))

/**
 * Verify the ed25519 signature on a parsed [Manifest] against a hex public key
 * (32 bytes → 64 hex chars). For a 2.0 manifest that key is
 * `course_cert.course_pubkey`; for 1.x it is the single embedded course key.
 * Returns false (never throws) on any malformed input.
 *
 * **This is NOT chain verification.** At 2.0 it answers only "did this public key
 * sign this payload", and the key you would naturally reach for —
 * `manifest.courseCert.coursePubkey` — comes out of a student-editable file.
 * Calling `verifyManifest(m, m.courseCert!!.coursePubkey)` and treating the result
 * as trust is self-referential and proves nothing. Use [verifyManifestChain],
 * which roots the key in the root signature first.
 *
 * This entry point is unchanged for 1.x callers: same signature, same bytes, same
 * result.
 */
fun verifyManifest(manifest: Manifest, pubkeyHex: String): Boolean {
    if (!HEX_64_RE.matches(pubkeyHex)) return false
    if (!HEX_128_RE.matches(manifest.sig)) return false
    return try {
        // Everything that can throw on hostile input lives inside the try:
        // hexToBytes on a malformed hex string, and buildSignedPayload, which
        // canonicalizes an attacker-influenced `policy` block.
        val sigBytes = Ed25519.hexToBytes(manifest.sig)
        val pubBytes = Ed25519.hexToBytes(pubkeyHex)
        Ed25519.verify(sigBytes, buildSignedPayload(manifest), pubBytes)
    } catch (_: Exception) {
        false
    }
}

/**
 * Walk the full Manifest 2.0 trust chain: root → `course_cert` → manifest.
 *
 * Program spec §3. **The steps run in this order and the order is load-bearing:**
 *
 *  0. `format_version` is `"2.0"` — otherwise there is no chain to walk, and
 *     walking one anyway is a downgrade attack (see [ManifestChain.NotManifest20]).
 *  0b. The 2.0 shape is valid, checked before any signature work: JCS omits absent
 *      keys, so a 2.0 manifest missing `policy` would sign and chain cleanly while
 *      carrying no policy at all.
 *  1. `course_cert` minus `root_sig` verifies against [rootPubkeyHex].
 *  2. The manifest payload (minus `sig` and `course_cert`) verifies against
 *     `course_cert.course_pubkey`.
 *  3. `manifest.course_id == course_cert.course_id`.
 *  4. `manifest.issued_at` falls within `[valid_from, valid_until]`.
 *
 * Step 3 is not a formality. Without it, CS 61B's course key can sign a manifest
 * whose `course_id` says `berkeley-cs61c`, and steps 1 and 2 both pass: the cert
 * is genuinely root-signed, and the payload is genuinely signed by the key that
 * cert authorizes. Only comparing the two ids catches it.
 *
 * Step 4 is evaluated against `issued_at`, NEVER against wall-clock now — a Fall
 * 2026 bundle must still verify in 2028 for an adjudication case. It is also
 * **non-fatal**: an out-of-window result is returned on [ManifestChain.Ok.window],
 * not as an error. A course that lets a cert lapse mid-semester must not have
 * recording silently stop for the whole class (program spec §4); the recorder
 * records and stamps the expiry, and the analyzer decides.
 *
 * @param rootPubkeyHex The embedded root public key. A PARAMETER — `core/` never
 *                      hardcodes it; each plugin build embeds its own.
 */
fun verifyManifestChain(manifest: Manifest, rootPubkeyHex: String): ManifestChain {
    // Step 0 — the chain exists only at 2.0.
    val declaredVersion = manifestFormatVersion(manifest)
    if (declaredVersion != MANIFEST_FORMAT_VERSION_2) {
        return ManifestChain.NotManifest20(declaredVersion)
    }

    val cert = manifest.courseCert ?: return ManifestChain.MissingCourseCert

    // Step 0b — re-validate the WHOLE manifest, not just the certificate: it may
    // have been hand-built, or (per program spec §5) lifted straight out of a
    // `session.start` payload without going through parseManifest.
    validateManifestShape(manifest)?.let { return ManifestChain.InvalidManifestShape(it) }

    // Step 1 — cert vs root.
    if (!verifyCourseCert(cert, rootPubkeyHex)) return ManifestChain.InvalidRootSignature

    // Step 2 — payload vs course_pubkey.
    if (!verifyManifest(manifest, cert.coursePubkey)) return ManifestChain.InvalidCourseSignature

    // Step 3 — the manifest may not claim a course its cert does not cover.
    if (manifest.courseId != cert.courseId) {
        return ManifestChain.CourseIdMismatch(manifest.courseId, cert.courseId)
    }

    // Step 4 — non-fatal validity window, evaluated against issued_at.
    return ManifestChain.Ok(
        courseId = cert.courseId,
        coursePubkey = cert.coursePubkey,
        cert = cert,
        window = checkCertWindow(cert, manifest.issuedAt),
    )
}

private fun JsonObject.nonEmptyString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}
