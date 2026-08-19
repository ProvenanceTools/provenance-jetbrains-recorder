package dev.provenance.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Enrollment certificate + enrollment token — the identity half of the trust chain
 * (program spec §S2). The Kotlin twin of log-core's `enrollment.ts`.
 *
 * Structurally parallel to `CourseCert.kt`: read that file first, this one
 * deliberately mirrors its shape, its result style, and its rules.
 *
 * ## The problem this layer exists to solve
 *
 * An enrollment token binds a student's per-course public key to a roster identity,
 * and must be signed by the course. But the course's manifest-signing key is
 * deliberately OFFLINE — that is most of what `course_cert` buys. Minting a token
 * per student per semester is a server-side, on-demand operation, so putting the
 * course key on a server to do it would defeat the whole design.
 *
 * The fix is one more delegation, exactly the shape of root → course:
 *
 * ```
 *   root keypair            (offline; signs course certs only)
 *        │ signs
 *        ▼
 *   course_cert             { course_id, course_pubkey, valid_from, valid_until }
 *        │ authorizes
 *        ▼
 *   course keypair          (OFFLINE; signs manifests AND enrollment certs)
 *        │ signs
 *        ▼
 *   enrollment_cert         { format_version, course_id, enrollment_pubkey,
 *                             valid_from, valid_until }
 *        │ authorizes
 *        ▼
 *   enrollment keypair      ◄── LIVES ON THE SERVER. The only private key in the
 *        │ signs                whole scheme that does.
 *        ▼
 *   enrollment token        { format_version, student_ref, course_id,
 *                             student_pubkey, issued_at, expires_at }
 *        │ authorizes
 *        ▼
 *   student per-course key  (derived on the student's machine; see StudentKeys.kt)
 *        │ countersigns
 *        ▼
 *   session_pubkey          (the existing ephemeral session key)
 * ```
 *
 * ## `student_ref` is opaque, and is a VALUE
 *
 * `student_ref` is an opaque UUID, never a raw SID, name, or email. In a shared CS
 * 61B repo one partner can read the other's `session.start`; the server maps
 * `student_ref` → `roster_entries.id`, so a partner sees only a UUID.
 *
 * It is also never an object KEY in a signed payload — see the permanent constraint
 * documented in `CourseCert.kt`. Every key in every payload below is a fixed ASCII
 * identifier chosen by us. For the same cross-port reason the signed payloads
 * contain **no JSON arrays**: the Lua port must tag arrays explicitly at each call
 * site, which is one more thing to get wrong. Objects only.
 *
 * ## Expiry is reported, never enforced
 *
 * Exactly as for `course_cert`: an out-of-window credential is NOT an error. It is
 * returned on the success value for the caller to act on. A course letting an
 * enrollment cert lapse mid-semester must not silently stop recording for the whole
 * class — for an integrity tool that is a worse failure than recording under a stale
 * credential (program spec §4).
 *
 * And every window is evaluated against **the relevant issue time**, never
 * wall-clock now, so an archived bundle still verifies years later:
 *
 *  - the enrollment cert's window is checked against the TOKEN's `issued_at`
 *    ("was the enrollment key authorized when it minted this token");
 *  - the token's window is checked against the SESSION's start time
 *    ("was this student enrolled when they did this work").
 *
 * ## Revocation
 *
 * Not modelled here, for the same reason as `course_cert`: an offline recorder
 * cannot learn about it without a network call, which recorder PRD NG2 forbids. A
 * server-side list must key on `enrollment_pubkey` and on `student_ref`, not on a
 * certificate or token identity — both travel outside any payload that binds to
 * them, so the holder chooses which copy ships. The offline mitigation is short
 * windows.
 */

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * The version at which the identity chain exists. Both the enrollment cert and the
 * enrollment token carry it INSIDE their signed payloads, and [verifyIdentityChain]
 * gates on it before walking anything.
 *
 * There is no 1.x identity artifact — this layer is new — so unlike
 * `manifest.format_version` there is nothing to default and nothing to grandfather.
 * The field exists purely so a future 3.0 cannot be presented as a 2.0 artifact:
 * because it is signed, a downgraded 3.0 token fails signature verification rather
 * than being silently read under 2.0 rules. That is the S0 lesson, applied before it
 * can bite rather than after.
 */
const val ENROLLMENT_FORMAT_VERSION: String = "2.0"

/** Fixed domain-separation tag for the session-pubkey countersignature. */
const val SESSION_PUBKEY_BINDING_PURPOSE: String = "provenance-session-pubkey-binding-v1"

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * A course-signed statement that an ENROLLMENT key may mint tokens for a course.
 *
 * The middle link of the identity chain, and the reason the course key can stay
 * offline. Travels inline next to the token (see [SessionIdentity]), outside any
 * payload that signs it — same distribution logic as `course_cert` inside a
 * manifest: one thing to carry, no chance of separation.
 */
data class EnrollmentCert(
    /** Must be [ENROLLMENT_FORMAT_VERSION]. Inside the signed payload. */
    val formatVersion: String,
    /** Must equal the enclosing `course_cert.course_id` and the token's `course_id`. */
    val courseId: String,
    /** Hex ed25519 public key of the server-held enrollment signing key, 64 chars. */
    val enrollmentPubkey: String,
    /**
     * Inclusive lower bound. ISO 8601 date or timestamp. A date-only value means the
     * first instant of that day (UTC midnight).
     */
    val validFrom: String,
    /**
     * Inclusive upper bound. A date-only value means THROUGH THE END of that day; a
     * full timestamp means exactly that instant. Same asymmetric resolution as
     * `course_cert.valid_until` — see [resolveValidUntilExclusiveMs].
     */
    val validUntil: String,
    /** Hex ed25519 signature by the COURSE key, 128 chars (64 bytes). */
    val courseSig: String,
)

/**
 * An enrollment-signed statement that a student public key belongs to a roster entry
 * in a course.
 *
 * Signed by the ENROLLMENT key (which `enrollment_cert` authorizes), NOT by the
 * course key directly — the course key is offline and cannot mint per-student tokens
 * on demand.
 */
data class EnrollmentToken(
    /** Must be [ENROLLMENT_FORMAT_VERSION]. Inside the signed payload. */
    val formatVersion: String,
    /** Opaque roster reference. Never a student ID number, name, or email. */
    val studentRef: String,
    val courseId: String,
    /** Hex ed25519 public key of the student's per-course key, 64 chars. */
    val studentPubkey: String,
    /** ISO 8601. Also the instant the enrollment cert's window is judged against. */
    val issuedAt: String,
    /** ISO 8601. Date-only resolves through the end of that day. */
    val expiresAt: String,
    /** Hex ed25519 signature by the ENROLLMENT key, 128 chars (64 bytes). */
    val enrollmentSig: String,
)

/**
 * The identity block carried in `session.start` 2.0 (program spec §5).
 *
 * `enrollmentCert` travels here, beside the token rather than inside it, for the
 * same reason `course_cert` travels inside the manifest rather than inside the
 * course-signed payload: the issuer does not sign its own authorization, and one
 * bundled blob cannot be separated from the thing it authorizes.
 */
data class SessionIdentity(
    val enrollment: EnrollmentToken,
    /** The course-signed authorization for whichever key signed [enrollment]. */
    val enrollmentCert: EnrollmentCert,
    /**
     * The student per-course key's signature over the session's ephemeral
     * `session_pubkey`. This is the link that binds an ephemeral session key to a
     * named contributor. See [buildSessionPubkeyBindingPayload].
     */
    val sessionPubkeySig: String,
)

/** The three fields the student per-course key countersigns. */
data class SessionPubkeyBinding(
    val courseId: String,
    val studentRef: String,
    val sessionPubkey: String,
)

sealed interface EnrollmentParse<out T> {
    data class Ok<T>(val value: T) : EnrollmentParse<T>

    data class Err(val reason: String) : EnrollmentParse<Nothing>
}

/**
 * Outcome of [verifyIdentityChain]. Out-of-window results are deliberately NOT
 * failures — they are non-fatal and are reported on [Ok] instead.
 */
sealed interface IdentityChain {
    data class Ok(
        /** The course all three links agree on. */
        val courseId: String,
        /** The roster reference this session is attributed to. Opaque. */
        val studentRef: String,
        /** The student per-course public key that countersigned `session_pubkey`. */
        val studentPubkey: String,
        /** The enrollment public key the course vouched for. */
        val enrollmentPubkey: String,
        val cert: EnrollmentCert,
        val token: EnrollmentToken,
        /**
         * Non-fatal. Was the enrollment cert in window when it minted this token?
         * Judged against `token.issuedAt`, never wall-clock now.
         */
        val certWindow: CertWindowStatus,
        /**
         * Non-fatal. Was the token in window when this session ran? Judged against
         * the supplied session start time, never wall-clock now.
         */
        val tokenWindow: CertWindowStatus,
    ) : IdentityChain

    /** Every way the chain walk can fail. [kind] is the wire name log-core uses. */
    sealed interface Err : IdentityChain {
        val kind: String
    }

    /**
     * Step 0: an artifact declares a version whose rules this code does not
     * implement. Gated before any signature work, so a future format cannot be
     * walked under today's assumptions about which fields are signed.
     */
    data class NotEnrollment20(
        /** `"cert"` or `"token"`. */
        val artifact: String,
        val formatVersion: String,
    ) : Err {
        override val kind: String get() = "not_enrollment_2_0"
    }

    /**
     * Step 0b: the enrollment cert does not satisfy the 2.0 shape. Reported before
     * signature work because JCS OMITS keys whose value is absent — an artifact
     * missing a required field would otherwise sign and verify cleanly while
     * carrying nothing at that field.
     */
    data class InvalidCertShape(val reason: String) : Err {
        override val kind: String get() = "invalid_cert_shape"
    }

    /** Step 0b, same reasoning, for the token. */
    data class InvalidTokenShape(val reason: String) : Err {
        override val kind: String get() = "invalid_token_shape"
    }

    /** Step 1: `enrollment_cert` does not verify against `course_cert.course_pubkey`. */
    data object InvalidCourseSignature : Err {
        override val kind: String get() = "invalid_course_signature"
    }

    /** Step 2: the token does not verify against `enrollment_cert.enrollment_pubkey`. */
    data object InvalidEnrollmentSignature : Err {
        override val kind: String get() = "invalid_enrollment_signature"
    }

    /** Step 3: the three links do not all name the same course. */
    data class CourseIdMismatch(
        val tokenCourseId: String,
        val certCourseId: String,
        val courseCertCourseId: String,
    ) : Err {
        override val kind: String get() = "course_id_mismatch"
    }

    /** Step 4: the supplied session public key is not a 64-char hex string. */
    data object InvalidSessionPubkey : Err {
        override val kind: String get() = "invalid_session_pubkey"
    }

    /** Step 4: `session_pubkey_sig` does not verify against `token.student_pubkey`. */
    data object InvalidSessionPubkeySignature : Err {
        override val kind: String get() = "invalid_session_pubkey_signature"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val ENROLL_HEX_128_RE = Regex("^[0-9a-f]{128}$")
private val ENROLL_HEX_64_RE = Regex("^[0-9a-f]{64}$")

/**
 * A required non-empty string field.
 *
 * A missing key and a JSON-null-valued key are treated identically — JCS erases the
 * difference, so nothing downstream can rely on it.
 */
private fun JsonObject.requireString(field: String): String? {
    val p = this[field] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}

/**
 * Validate an ordered pair of ISO 8601 bounds.
 *
 * Both bounds MUST parse. Short validity windows are the only offline mitigation
 * this scheme has for the absence of revocation, so a bound that silently never
 * binds would undercut the sole control there is. These artifacts are new, so unlike
 * `manifest.issued_at` there is no archived-data compatibility cost to enforcing it.
 *
 * Returns the two raw strings, or an error reason.
 */
private fun JsonObject.requireOrderedBounds(
    lowerField: String,
    upperField: String,
): EnrollmentParse<Pair<String, String>> {
    val values = mutableListOf<String>()
    val instants = mutableListOf<Long>()
    for (field in listOf(lowerField, upperField)) {
        val raw = requireString(field)
            ?: return EnrollmentParse.Err("invalid_shape: $field must be a non-empty string")
        val ms = parseIsoInstantMs(raw)
            ?: return EnrollmentParse.Err("invalid_shape: $field must be an ISO 8601 date or timestamp")
        values += raw
        instants += ms
    }
    if (instants[0] > instants[1]) {
        return EnrollmentParse.Err("invalid_shape: $upperField must not be earlier than $lowerField")
    }
    return EnrollmentParse.Ok(values[0] to values[1])
}

/**
 * Shared window arithmetic: is [at] inside `[lower, upper]`?
 *
 * [lower] is inclusive from its first instant; a date-only [upper] is inclusive
 * through the END of that day, via [resolveValidUntilExclusiveMs]. Identical
 * semantics to [checkCertWindow], so a port implements the rule once.
 */
private fun checkWindow(lower: String, upper: String, at: String): CertWindowStatus {
    val from = parseIsoInstantMs(lower)
    val untilExclusive = resolveValidUntilExclusiveMs(upper)
    val instant = parseIsoInstantMs(at)

    if (from == null || untilExclusive == null || instant == null) {
        return CertWindowStatus.OutOfWindow(CertWindowReason.UNPARSEABLE_TIMESTAMP)
    }
    if (instant < from) return CertWindowStatus.OutOfWindow(CertWindowReason.BEFORE_VALID_FROM)
    if (instant >= untilExclusive) return CertWindowStatus.OutOfWindow(CertWindowReason.AFTER_VALID_UNTIL)
    return CertWindowStatus.InWindow
}

/**
 * Shared ed25519 verification. Every malformed input is a verification FAILURE
 * rather than an exception: these are values arriving from a student-editable file,
 * so a bad hex string is an expected condition.
 */
private fun verifyDetached(payload: ByteArray, sigHex: String, pubkeyHex: String): Boolean {
    if (!ENROLL_HEX_128_RE.matches(sigHex) || !ENROLL_HEX_64_RE.matches(pubkeyHex)) return false
    return try {
        Ed25519.verify(Ed25519.hexToBytes(sigHex), payload, Ed25519.hexToBytes(pubkeyHex))
    } catch (_: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// Signed payloads — the exact bytes three ports must reproduce
// ---------------------------------------------------------------------------

/**
 * Build the canonical UTF-8 bytes the COURSE key signs for an enrollment cert.
 *
 * `course_sig` is excluded; the five remaining fields are canonicalized. JCS orders
 * keys, so the literal order below is irrelevant to the output — the resulting key
 * order is always:
 *
 *   course_id, enrollment_pubkey, format_version, valid_from, valid_until
 */
fun buildEnrollmentCertSignedPayload(cert: EnrollmentCert): ByteArray {
    val payload = buildJsonObject {
        put("course_id", cert.courseId)
        put("enrollment_pubkey", cert.enrollmentPubkey)
        put("format_version", cert.formatVersion)
        put("valid_from", cert.validFrom)
        put("valid_until", cert.validUntil)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Build the canonical UTF-8 bytes the ENROLLMENT key signs for a token.
 *
 * `enrollment_sig` is excluded; the six remaining fields are canonicalized. The
 * resulting JCS key order is always:
 *
 *   course_id, expires_at, format_version, issued_at, student_pubkey, student_ref
 *
 * Note `student_ref` appears as a VALUE at a fixed ASCII key — never promoted to a
 * key itself.
 */
fun buildEnrollmentTokenSignedPayload(token: EnrollmentToken): ByteArray {
    val payload = buildJsonObject {
        put("course_id", token.courseId)
        put("expires_at", token.expiresAt)
        put("format_version", token.formatVersion)
        put("issued_at", token.issuedAt)
        put("student_pubkey", token.studentPubkey)
        put("student_ref", token.studentRef)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Build the canonical UTF-8 bytes the STUDENT per-course key signs to bind an
 * ephemeral `session_pubkey` to itself.
 *
 * A bare 64-char hex string would have been the minimal thing to sign. It is not
 * what is signed, for two reasons:
 *
 *  - **Domain separation.** A signature over an unstructured blob is a signature
 *    over anything that blob might also mean. The fixed `purpose` tag makes this
 *    message unmistakably this message, and leaves room to add a second thing the
 *    student key signs later without the two being confusable.
 *  - **Self-describing binding.** Including `course_id` and `student_ref` means the
 *    countersignature itself asserts which student, in which course, adopted this
 *    session key. Verification therefore cross-checks those values against the token
 *    rather than taking them on trust from elsewhere in the payload.
 *
 * JCS key order is always: course_id, purpose, session_pubkey, student_ref.
 */
fun buildSessionPubkeyBindingPayload(binding: SessionPubkeyBinding): ByteArray {
    val payload = buildJsonObject {
        put("course_id", binding.courseId)
        put("purpose", SESSION_PUBKEY_BINDING_PURPOSE)
        put("session_pubkey", binding.sessionPubkey)
        put("student_ref", binding.studentRef)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

// ---------------------------------------------------------------------------
// Shape validation
// ---------------------------------------------------------------------------

/**
 * Validate the shape of an already-JSON-parsed enrollment cert.
 *
 * Takes a [JsonElement] rather than text because the cert travels inline inside a
 * `session.start` payload. Unknown keys are ignored for forward compatibility, which
 * is safe: canonicalization operates on the five named fields only, so an unknown key
 * cannot silently change the signed bytes.
 *
 * This does NOT check `format_version` — [verifyIdentityChain] gates on that first,
 * and reports it as a distinct error so a version problem is never mistaken for a
 * malformed artifact.
 */
fun parseEnrollmentCert(value: JsonElement?): EnrollmentParse<EnrollmentCert> {
    val obj = value as? JsonObject
        ?: return EnrollmentParse.Err("invalid_shape: must be an object")

    val formatVersion = obj.requireString("format_version")
        ?: return EnrollmentParse.Err("invalid_shape: format_version must be a non-empty string")
    val courseId = obj.requireString("course_id")
        ?: return EnrollmentParse.Err("invalid_shape: course_id must be a non-empty string")

    val bounds = when (val b = obj.requireOrderedBounds("valid_from", "valid_until")) {
        is EnrollmentParse.Err -> return b
        is EnrollmentParse.Ok -> b.value
    }

    val enrollmentPubkey = obj.requireString("enrollment_pubkey")
    if (enrollmentPubkey == null || !ENROLL_HEX_64_RE.matches(enrollmentPubkey)) {
        return EnrollmentParse.Err("invalid_shape: enrollment_pubkey must be a 64-char hex string")
    }
    val courseSig = obj.requireString("course_sig")
    if (courseSig == null || !ENROLL_HEX_128_RE.matches(courseSig)) {
        return EnrollmentParse.Err("invalid_shape: course_sig must be a 128-char hex string")
    }

    return EnrollmentParse.Ok(
        EnrollmentCert(
            formatVersion = formatVersion,
            courseId = courseId,
            enrollmentPubkey = enrollmentPubkey,
            validFrom = bounds.first,
            validUntil = bounds.second,
            courseSig = courseSig,
        ),
    )
}

/**
 * Validate the shape of an already-JSON-parsed enrollment token. Unknown keys are
 * ignored, for the same reason as [parseEnrollmentCert].
 */
fun parseEnrollmentToken(value: JsonElement?): EnrollmentParse<EnrollmentToken> {
    val obj = value as? JsonObject
        ?: return EnrollmentParse.Err("invalid_shape: must be an object")

    val formatVersion = obj.requireString("format_version")
        ?: return EnrollmentParse.Err("invalid_shape: format_version must be a non-empty string")
    val studentRef = obj.requireString("student_ref")
        ?: return EnrollmentParse.Err("invalid_shape: student_ref must be a non-empty string")
    val courseId = obj.requireString("course_id")
        ?: return EnrollmentParse.Err("invalid_shape: course_id must be a non-empty string")

    val bounds = when (val b = obj.requireOrderedBounds("issued_at", "expires_at")) {
        is EnrollmentParse.Err -> return b
        is EnrollmentParse.Ok -> b.value
    }

    val studentPubkey = obj.requireString("student_pubkey")
    if (studentPubkey == null || !ENROLL_HEX_64_RE.matches(studentPubkey)) {
        return EnrollmentParse.Err("invalid_shape: student_pubkey must be a 64-char hex string")
    }
    val enrollmentSig = obj.requireString("enrollment_sig")
    if (enrollmentSig == null || !ENROLL_HEX_128_RE.matches(enrollmentSig)) {
        return EnrollmentParse.Err("invalid_shape: enrollment_sig must be a 128-char hex string")
    }

    return EnrollmentParse.Ok(
        EnrollmentToken(
            formatVersion = formatVersion,
            studentRef = studentRef,
            courseId = courseId,
            studentPubkey = studentPubkey,
            issuedAt = bounds.first,
            expiresAt = bounds.second,
            enrollmentSig = enrollmentSig,
        ),
    )
}

// ---------------------------------------------------------------------------
// Signing — course/server tooling and the conformance-vector generator only.
// A recorder never calls the first two; it only ever verifies.
// ---------------------------------------------------------------------------

/** Sign an enrollment cert with the COURSE private key (offline operation). */
fun signEnrollmentCert(cert: EnrollmentCert, coursePrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildEnrollmentCertSignedPayload(cert), coursePrivkey32))

/** Sign an enrollment token with the ENROLLMENT private key (server-side). */
fun signEnrollmentToken(token: EnrollmentToken, enrollmentPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildEnrollmentTokenSignedPayload(token), enrollmentPrivkey32))

/**
 * Countersign a session public key with the student's per-course private key.
 * Called by the recorder at session start — the one signing operation on this path
 * that happens on the student's machine.
 */
fun signSessionPubkey(binding: SessionPubkeyBinding, studentPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildSessionPubkeyBindingPayload(binding), studentPrivkey32))

// ---------------------------------------------------------------------------
// Single-link verification
// ---------------------------------------------------------------------------

/**
 * Identity chain step 1: verify an enrollment cert against the course public key
 * that a root-verified `course_cert` vouched for.
 *
 * @param coursePubkeyHex MUST come from an already-root-verified `course_cert`.
 *                        Reading it from anywhere else makes this check circular.
 */
fun verifyEnrollmentCert(cert: EnrollmentCert, coursePubkeyHex: String): Boolean =
    verifyDetached(buildEnrollmentCertSignedPayload(cert), cert.courseSig, coursePubkeyHex)

/**
 * Identity chain step 2: verify an enrollment token against the enrollment public
 * key the course certified.
 */
fun verifyEnrollmentToken(token: EnrollmentToken, enrollmentPubkeyHex: String): Boolean =
    verifyDetached(buildEnrollmentTokenSignedPayload(token), token.enrollmentSig, enrollmentPubkeyHex)

/**
 * Identity chain step 4: verify that the student per-course key named by a token
 * countersigned this session's ephemeral public key.
 */
fun verifySessionPubkeySig(
    binding: SessionPubkeyBinding,
    sigHex: String,
    studentPubkeyHex: String,
): Boolean = verifyDetached(buildSessionPubkeyBindingPayload(binding), sigHex, studentPubkeyHex)

// ---------------------------------------------------------------------------
// Window checks — non-fatal, never against wall-clock now
// ---------------------------------------------------------------------------

/**
 * Was [token] in window at [at]?
 *
 * [at] is the SESSION start time, not wall-clock now: a Fall 2026 session must still
 * read as in-window in 2030. Reuses [CertWindowStatus], so `BEFORE_VALID_FROM` here
 * means "before the token was issued" and `AFTER_VALID_UNTIL` means "after it
 * expired" — one status vocabulary for every window in the system.
 */
fun checkTokenWindow(token: EnrollmentToken, at: String): CertWindowStatus =
    checkWindow(token.issuedAt, token.expiresAt, at)

/**
 * Was [cert] in window at [at]? [at] is the TOKEN's `issued_at` when called from
 * [verifyIdentityChain] — "was this enrollment key authorized when it minted that
 * token".
 */
fun checkEnrollmentCertWindow(cert: EnrollmentCert, at: String): CertWindowStatus =
    checkWindow(cert.validFrom, cert.validUntil, at)

// ---------------------------------------------------------------------------
// The full identity chain
// ---------------------------------------------------------------------------

/**
 * Walk the identity chain: course_cert → enrollment_cert → token → session_pubkey_sig.
 *
 * **The steps run in this order and the order is load-bearing**, mirroring
 * [verifyManifestChain]:
 *
 *  0. Both artifacts declare `format_version == "2.0"`. Gated before any signature
 *     work — see [IdentityChain.NotEnrollment20].
 *  0b. Both artifacts satisfy the 2.0 shape. Also before signature work: JCS omits
 *     absent keys, so an artifact missing a required field signs and verifies
 *     cleanly while carrying nothing there.
 *  1. `enrollment_cert` minus `course_sig` verifies against `courseCert.coursePubkey`.
 *  2. The token minus `enrollment_sig` verifies against `cert.enrollmentPubkey`.
 *  3. `token.courseId == cert.courseId == courseCert.courseId`.
 *  4. `sessionPubkeySig` verifies against `token.studentPubkey` over the binding
 *     payload for this exact session pubkey.
 *  5. Both validity windows — NON-FATAL, returned on the success value.
 *
 * Step 3 is not a formality, and it is why all THREE ids are compared rather than
 * two. Without it, 61B's course key can certify an enrollment key "for 61C", that
 * key can mint a 61C token, and steps 1 and 2 both pass: every signature is genuine.
 * Only comparing ids across every link catches a cross-course forgery, and the
 * requirement is that it be impossible, not merely unlikely.
 *
 * ## The `courseCert` MUST already be verified
 *
 * This function takes the course certificate as a trust anchor and does NOT re-verify
 * it against the root key — exactly as [verifyCourseCert] takes the root public key
 * as a parameter rather than knowing one. The caller is responsible for having
 * obtained it from a successful [verifyManifestChain]. Passing an unverified cert
 * makes every result below meaningless, because an attacker who supplies the cert
 * supplies `course_pubkey` too and can then satisfy the entire chain with keys of
 * their own.
 *
 * @param identity         The `session.start` identity block.
 * @param sessionPubkey    The session's ephemeral public key, 64-char hex.
 * @param courseCert       An ALREADY ROOT-VERIFIED course certificate.
 * @param sessionStartedAt ISO 8601 session start; the token's window is judged
 *                         against this, never wall-clock now.
 */
fun verifyIdentityChain(
    identity: SessionIdentity,
    sessionPubkey: String,
    courseCert: CourseCert,
    sessionStartedAt: String,
): IdentityChain {
    // Step 0 — version gate, before anything is trusted or verified. Reading the
    // declared version off an unvalidated artifact is safe precisely because nothing
    // else has happened yet.
    if (identity.enrollmentCert.formatVersion != ENROLLMENT_FORMAT_VERSION) {
        return IdentityChain.NotEnrollment20("cert", identity.enrollmentCert.formatVersion)
    }
    if (identity.enrollment.formatVersion != ENROLLMENT_FORMAT_VERSION) {
        return IdentityChain.NotEnrollment20("token", identity.enrollment.formatVersion)
    }

    // Step 0b — shape before signatures, for both artifacts. Re-validated from their
    // serialized form so a hand-built artifact gets the same treatment as a parsed
    // one; see verifyManifestChain for the same argument.
    val cert = when (val c = parseEnrollmentCert(identity.enrollmentCert.toJsonObject())) {
        is EnrollmentParse.Err -> return IdentityChain.InvalidCertShape(c.reason)
        is EnrollmentParse.Ok -> c.value
    }
    val token = when (val t = parseEnrollmentToken(identity.enrollment.toJsonObject())) {
        is EnrollmentParse.Err -> return IdentityChain.InvalidTokenShape(t.reason)
        is EnrollmentParse.Ok -> t.value
    }

    // Step 1 — enrollment cert vs the course key the root vouched for.
    if (!verifyEnrollmentCert(cert, courseCert.coursePubkey)) {
        return IdentityChain.InvalidCourseSignature
    }

    // Step 2 — token vs the enrollment key the course certified.
    if (!verifyEnrollmentToken(token, cert.enrollmentPubkey)) {
        return IdentityChain.InvalidEnrollmentSignature
    }

    // Step 3 — every link must name the same course.
    if (token.courseId != cert.courseId || cert.courseId != courseCert.courseId) {
        return IdentityChain.CourseIdMismatch(token.courseId, cert.courseId, courseCert.courseId)
    }

    // Step 4 — the student key adopted THIS session key.
    if (!ENROLL_HEX_64_RE.matches(sessionPubkey)) {
        return IdentityChain.InvalidSessionPubkey
    }
    val binding = SessionPubkeyBinding(
        courseId = token.courseId,
        studentRef = token.studentRef,
        sessionPubkey = sessionPubkey,
    )
    if (!verifySessionPubkeySig(binding, identity.sessionPubkeySig, token.studentPubkey)) {
        return IdentityChain.InvalidSessionPubkeySignature
    }

    // Step 5 — non-fatal windows, each against its own relevant issue time.
    return IdentityChain.Ok(
        courseId = token.courseId,
        studentRef = token.studentRef,
        studentPubkey = token.studentPubkey,
        enrollmentPubkey = cert.enrollmentPubkey,
        cert = cert,
        token = token,
        certWindow = checkEnrollmentCertWindow(cert, token.issuedAt),
        tokenWindow = checkTokenWindow(token, sessionStartedAt),
    )
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/**
 * Serialize an enrollment cert to its on-wire JSON shape. Emits all six fields,
 * `course_sig` included: unlike [buildEnrollmentCertSignedPayload] this is transport,
 * not the signed payload.
 */
fun EnrollmentCert.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("course_id", courseId)
    put("enrollment_pubkey", enrollmentPubkey)
    put("valid_from", validFrom)
    put("valid_until", validUntil)
    put("course_sig", courseSig)
}

/** Serialize an enrollment token to its on-wire JSON shape, `enrollment_sig` included. */
fun EnrollmentToken.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("student_ref", studentRef)
    put("course_id", courseId)
    put("student_pubkey", studentPubkey)
    put("issued_at", issuedAt)
    put("expires_at", expiresAt)
    put("enrollment_sig", enrollmentSig)
}

/**
 * Serialize the identity block for `session.start` 2.0 (program spec §5).
 *
 * NOT wired into `session.start` yet: emission waits on the server's enrollment
 * endpoint, so a later task adds it. Defined here so the transport shape lives beside
 * the artifacts it carries.
 */
fun SessionIdentity.toJsonObject(): JsonObject = buildJsonObject {
    put("enrollment", enrollment.toJsonObject())
    put("enrollment_cert", enrollmentCert.toJsonObject())
    put("session_pubkey_sig", sessionPubkeySig)
}
