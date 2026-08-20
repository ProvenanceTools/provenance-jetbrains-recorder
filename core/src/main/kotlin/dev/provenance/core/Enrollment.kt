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
    override val formatVersion: String,
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
) : IdentityCert

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
    override val formatVersion: String,
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
) : IdentityCredential

/**
 * The identity block carried in `session.start` (program spec §5).
 *
 * TWO SHAPES SHARE THESE TWO WIRE SLOTS, distinguished by the signed `formatVersion`
 * inside [enrollmentCert]:
 *
 *  - **`"2.0"` — legacy, COURSE-scoped.** [enrollmentCert] is an [EnrollmentCert]
 *    (course-signed) and [enrollment] is an [EnrollmentToken]. Every archived bundle
 *    in the field carries this, and it is supported FOREVER: adjudicating a case
 *    years later is the entire justification for this system.
 *  - **`"2.1"` — current, INSTITUTION-scoped.** [enrollmentCert] is an
 *    [InstitutionCert] (ROOT-signed) and [enrollment] is a [StudentCredential]. See
 *    `Institution.kt` for why identity stopped being course-scoped.
 *
 * **The wire slot names are historical and deliberately unchanged.** `enrollment`
 * means "the credential"; `enrollment_cert` means "the authorization for whoever
 * signed it". Renaming them for 2.1 would have forced the version discriminator to be
 * found by looking at WHICH FIELDS EXIST — and this project has already been burned
 * at exactly that spot. One stable slot carrying a signed version is the shape that
 * cannot repeat that bug.
 *
 * In both versions the cert travels here, BESIDE the credential rather than inside
 * it, for the same reason `course_cert` travels inside the manifest rather than
 * inside the course-signed payload: an issuer does not sign its own authorization,
 * and one bundled blob cannot be separated from the thing it authorizes.
 */
data class SessionIdentity(
    /** The credential: a 2.0 [EnrollmentToken] or a 2.1 [StudentCredential]. */
    val enrollment: IdentityCredential,
    /**
     * The authorization for whichever key signed [enrollment], and the artifact whose
     * SIGNED `formatVersion` selects the walk: a 2.0 [EnrollmentCert] (course-signed)
     * or a 2.1 [InstitutionCert] (root-signed).
     */
    val enrollmentCert: IdentityCert,
    /**
     * The student key's signature over the session's ephemeral `session_pubkey`. This
     * is the link that binds an ephemeral session key to a named contributor. See
     * [buildSessionPubkeyBindingPayload] (2.0) and [buildStudentSessionBindingPayload]
     * (2.1) — the two payloads carry different `purpose` tags, so a countersignature
     * can never be replayed across versions.
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
 * Outcome of [verifyIdentityChain], for EITHER identity version. Out-of-window
 * results are deliberately NOT failures — they are non-fatal and are reported on
 * [Ok] instead.
 */
sealed interface IdentityChain {
    /**
     * A successfully walked chain, discriminated by the identity version that was
     * walked.
     *
     * [studentRef], [studentPubkey], [certWindow] and [tokenWindow] are declared HERE
     * rather than on each branch, under the same names log-core uses, so a caller
     * that only needs "who is this, and were their credentials in date" reads them
     * without narrowing. The window names in particular are kept identical across
     * versions on purpose: the three ports iterate the conformance vectors
     * generically, and a renamed field there is a port-level break for no benefit.
     */
    sealed interface Ok : IdentityChain {
        /** `"2.0"` or `"2.1"` — the version whose rules were applied. */
        val identityVersion: String

        /** `"course"` for 2.0, `"institution"` for 2.1. */
        val scope: String

        /** The roster reference this session is attributed to. Opaque. */
        val studentRef: String

        /** The student public key that countersigned `session_pubkey`. */
        val studentPubkey: String

        /**
         * Non-fatal. Was the issuing cert in window when it issued the credential?
         * Judged against the credential's own issue time, never wall-clock now.
         */
        val certWindow: CertWindowStatus

        /**
         * Non-fatal. Was the credential in window when this session ran? Judged
         * against the supplied session start time, never wall-clock now.
         */
        val tokenWindow: CertWindowStatus
    }

    /** Legacy COURSE-scoped identity, 2.0. Kept forever for archived bundles. */
    data class CourseOk(
        /** The course all three links agree on. */
        val courseId: String,
        /** Opaque, PER-COURSE. */
        override val studentRef: String,
        /** The student per-course public key that countersigned `session_pubkey`. */
        override val studentPubkey: String,
        /** The enrollment public key the course vouched for. */
        val enrollmentPubkey: String,
        val cert: EnrollmentCert,
        val token: EnrollmentToken,
        override val certWindow: CertWindowStatus,
        override val tokenWindow: CertWindowStatus,
    ) : Ok {
        override val identityVersion: String get() = ENROLLMENT_FORMAT_VERSION
        override val scope: String get() = "course"
    }

    /** Current INSTITUTION-scoped identity, 2.1. See `Institution.kt`. */
    data class InstitutionOk(
        /** The institution the credential, its cert, and the anchor all agree on. */
        val institutionId: String,
        /** Opaque and GLOBAL — one per student, across every course. */
        override val studentRef: String,
        /** The student's single long-lived public key. */
        override val studentPubkey: String,
        /** The institution public key the ROOT vouched for. */
        val institutionPubkey: String,
        val cert: InstitutionCert,
        val credential: StudentCredential,
        override val certWindow: CertWindowStatus,
        override val tokenWindow: CertWindowStatus,
    ) : Ok {
        override val identityVersion: String get() = INSTITUTION_IDENTITY_FORMAT_VERSION
        override val scope: String get() = "institution"
    }

    /** Every way the chain walk can fail. [kind] is the wire name log-core uses. */
    sealed interface Err : IdentityChain {
        val kind: String
    }

    /**
     * Step 0: the identity block declares a version whose rules this code does not
     * implement. Gated before any signature work, so a future 3.0 cannot be walked
     * under today's assumptions about which fields are signed.
     *
     * Read off the CERT slot, which carries the discriminator in both versions.
     */
    data class UnsupportedIdentityVersion(val formatVersion: String) : Err {
        override val kind: String get() = "unsupported_identity_version"
    }

    /**
     * Step 0: the cert and the credential declare DIFFERENT versions. Refused rather
     * than resolved: allowing a mix would let a legacy course-signed cert be paired
     * with an institution credential, and each artifact would then be read under
     * rules the other never agreed to.
     */
    data class IdentityVersionMismatch(
        val certVersion: String,
        val credentialVersion: String,
    ) : Err {
        override val kind: String get() = "identity_version_mismatch"
    }

    /**
     * Step 0: the caller did not supply the trust anchor this version's walk needs —
     * a `course_cert` for 2.0, an `institution_cert` for 2.1. A programmer error at
     * the call site, reported as a value because which anchor is needed is only known
     * after reading the bundle.
     */
    data class MissingTrustAnchor(
        /** `"course_cert"` or `"institution_cert"`. */
        val required: String,
    ) : Err {
        override val kind: String get() = "missing_trust_anchor"
    }

    /**
     * Step 0b: the cert does not satisfy its version's shape. Reported before
     * signature work because JCS OMITS keys whose value is absent — an artifact
     * missing a required field would otherwise sign and verify cleanly while carrying
     * nothing at that field.
     */
    data class InvalidCertShape(val reason: String) : Err {
        override val kind: String get() = "invalid_cert_shape"
    }

    /** Step 0b, same reasoning, for the credential. */
    data class InvalidTokenShape(val reason: String) : Err {
        override val kind: String get() = "invalid_token_shape"
    }

    /** 2.0 step 1: `enrollment_cert` does not verify against `course_cert.course_pubkey`. */
    data object InvalidCourseSignature : Err {
        override val kind: String get() = "invalid_course_signature"
    }

    /** 2.0 step 2: the token does not verify against `enrollment_cert.enrollment_pubkey`. */
    data object InvalidEnrollmentSignature : Err {
        override val kind: String get() = "invalid_enrollment_signature"
    }

    /** 2.0 step 3: the three links do not all name the same course. */
    data class CourseIdMismatch(
        val tokenCourseId: String,
        val certCourseId: String,
        val courseCertCourseId: String,
    ) : Err {
        override val kind: String get() = "course_id_mismatch"
    }

    /** 2.1 step 1: the credential does not verify against the ANCHOR's `institution_pubkey`. */
    data object InvalidInstitutionSignature : Err {
        override val kind: String get() = "invalid_institution_signature"
    }

    /**
     * 2.1 step 2, the MANDATORY anchor check — the institution-scoped replacement for
     * [CourseIdMismatch]. The credential, the cert travelling with it, and the
     * root-verified anchor must all name the same institution, and the travelling
     * cert must name the anchor's key. Without it, an attacker holding a genuinely
     * root-certified institution key for one institution can mint a credential naming
     * ANOTHER, and every signature verifies.
     */
    data class InstitutionMismatch(
        val credentialInstitutionId: String,
        val certInstitutionId: String,
        val anchorInstitutionId: String,
        /** True when the travelling cert names a different key than the anchor. */
        val pubkeyMismatch: Boolean,
    ) : Err {
        override val kind: String get() = "institution_mismatch"
    }

    /** Both versions: the supplied session public key is not a 64-char hex string. */
    data object InvalidSessionPubkey : Err {
        override val kind: String get() = "invalid_session_pubkey"
    }

    /** Both versions: `session_pubkey_sig` does not verify against the student pubkey. */
    data object InvalidSessionPubkeySignature : Err {
        override val kind: String get() = "invalid_session_pubkey_signature"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
    if (enrollmentPubkey == null || !IDENTITY_HEX_64_RE.matches(enrollmentPubkey)) {
        return EnrollmentParse.Err("invalid_shape: enrollment_pubkey must be a 64-char hex string")
    }
    val courseSig = obj.requireString("course_sig")
    if (courseSig == null || !IDENTITY_HEX_128_RE.matches(courseSig)) {
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
    if (studentPubkey == null || !IDENTITY_HEX_64_RE.matches(studentPubkey)) {
        return EnrollmentParse.Err("invalid_shape: student_pubkey must be a 64-char hex string")
    }
    val enrollmentSig = obj.requireString("enrollment_sig")
    if (enrollmentSig == null || !IDENTITY_HEX_128_RE.matches(enrollmentSig)) {
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
 * Walk the identity chain, routing on the SIGNED identity version.
 *
 * Two families share the two wire slots in [SessionIdentity]:
 *
 *  - **2.0, COURSE-scoped:** `course_cert → enrollment_cert → token → session_pubkey_sig`.
 *  - **2.1, INSTITUTION-scoped:** `root → institution_cert → student_credential →
 *    session_pubkey_sig`. One delegation, not two.
 *
 * ## Step 0, the version gate — the same rule for both
 *
 *  0. The DISCRIMINATOR is the `format_version` inside the CERT slot. It is signed in
 *     both families and sits at the same wire key in both, so it can be read without
 *     first knowing which shape is present. **Never route on which fields exist**:
 *     presence is attacker-controlled and ambiguous, and this project already shipped
 *     that exact bug once, making a whole legacy path unreachable. A version that is
 *     neither 2.0 nor 2.1 is [IdentityChain.UnsupportedIdentityVersion], gated before
 *     any signature work so a future 3.0 is never walked under today's assumptions.
 *  0a. The credential's declared version must MATCH the cert's. A mixed pair is
 *     [IdentityChain.IdentityVersionMismatch] — refused rather than resolved, because
 *     otherwise each artifact is read under rules the other never agreed to.
 *
 * ## The 2.0 walk (unchanged, and kept FOREVER)
 *
 *  0b. Both artifacts satisfy the 2.0 shape. Before signature work: JCS omits absent
 *      keys, so an artifact missing a required field signs and verifies cleanly while
 *      carrying nothing there.
 *  1.  `enrollment_cert` minus `course_sig` verifies against `courseCert.coursePubkey`.
 *  2.  The token minus `enrollment_sig` verifies against `cert.enrollmentPubkey`.
 *  3.  `token.courseId == cert.courseId == courseCert.courseId`. Not a formality:
 *      without it 61B's course key can certify an enrollment key "for 61C", that key
 *      can mint a 61C token, and steps 1 and 2 both pass because every signature is
 *      genuine. Only comparing ids across every link catches a cross-course forgery.
 *  4.  `sessionPubkeySig` verifies against `token.studentPubkey` over the v1 binding.
 *  5.  Both validity windows — NON-FATAL, returned on the success value.
 *
 * ## The 2.1 walk
 *
 *  0b. Both artifacts satisfy the 2.1 shape. Same reasoning.
 *  1.  The credential minus `institution_sig` verifies against the **ANCHOR's**
 *      `institutionPubkey` — never the travelling cert's copy, so a swapped cert can
 *      never introduce a key of the attacker's choosing even if step 2 were somehow
 *      bypassed.
 *  2.  **The institution anchor check — the replacement for step 3 above, and
 *      mandatory for the same reason.** `credential.institutionId`, the travelling
 *      cert's `institutionId`, and the anchor's must all agree, AND the travelling
 *      cert must name the anchor's `institutionPubkey`. Root legitimately certifies
 *      many institutions; without this, a holder of a genuinely root-certified key
 *      for one institution can mint a credential naming ANOTHER and ship it with
 *      their own genuine cert, and every signature verifies. One signer's credential
 *      must never be replayable under another signer's authority.
 *  3.  `sessionPubkeySig` verifies against `credential.studentPubkey` over the **v2**
 *      binding payload (a distinct `purpose` tag, so 2.0 and 2.1 countersignatures can
 *      never be swapped).
 *  4.  Both validity windows — NON-FATAL, returned on the success value.
 *
 * ## The trust anchor MUST already be verified
 *
 * This function takes its anchor as a parameter and does NOT re-verify it against the
 * root key — exactly as [verifyCourseCert] and [verifyInstitutionCert] take the root
 * public key as a parameter rather than knowing one. The caller obtains a
 * `course_cert` from a successful [verifyManifestChain], or root-verifies the
 * `institution_cert` with [verifyInstitutionCert] before passing it. Passing an
 * unverified anchor makes every result meaningless, because an attacker who supplies
 * the anchor supplies its public key too.
 *
 * @param identity         The `session.start` identity block.
 * @param sessionPubkey    The session's ephemeral public key, 64-char hex.
 * @param courseCert       An ALREADY ROOT-VERIFIED course certificate. Required for a
 *                         2.0 chain, ignored by 2.1. Nullable rather than defaulted so
 *                         its POSITION is preserved for the 2.0 call sites that pass
 *                         it positionally — a defaulted parameter would have to move
 *                         after `sessionStartedAt` and silently break them.
 * @param sessionStartedAt ISO 8601 session start; the credential's window is judged
 *                         against this, never wall-clock now.
 * @param institutionCert  An ALREADY ROOT-VERIFIED institution certificate. Required
 *                         for a 2.1 chain, ignored by 2.0.
 */
fun verifyIdentityChain(
    identity: SessionIdentity,
    sessionPubkey: String,
    courseCert: CourseCert?,
    sessionStartedAt: String,
    institutionCert: InstitutionCert? = null,
): IdentityChain {
    // Step 0 — the version gate, before anything is trusted, parsed, or verified.
    // Reading the declared version off an unvalidated artifact is safe precisely
    // because nothing else has happened yet.
    val certVersion = identity.enrollmentCert.formatVersion
    if (certVersion != ENROLLMENT_FORMAT_VERSION &&
        certVersion != INSTITUTION_IDENTITY_FORMAT_VERSION
    ) {
        return IdentityChain.UnsupportedIdentityVersion(certVersion)
    }

    // Step 0a — no mixing. A legacy course-signed cert paired with an institution
    // credential would leave each artifact read under rules the other never agreed to.
    val credentialVersion = identity.enrollment.formatVersion
    if (credentialVersion != certVersion) {
        return IdentityChain.IdentityVersionMismatch(certVersion, credentialVersion)
    }

    return if (certVersion == INSTITUTION_IDENTITY_FORMAT_VERSION) {
        walkInstitutionChain(identity, sessionPubkey, institutionCert, sessionStartedAt)
    } else {
        walkCourseChain(identity, sessionPubkey, courseCert, sessionStartedAt)
    }
}

/**
 * The LEGACY 2.0 course-scoped walk. Unchanged behaviour, kept forever so an archived
 * bundle still verifies during an adjudication years from now.
 */
private fun walkCourseChain(
    identity: SessionIdentity,
    sessionPubkey: String,
    courseCert: CourseCert?,
    sessionStartedAt: String,
): IdentityChain {
    if (courseCert == null) return IdentityChain.MissingTrustAnchor("course_cert")

    // Step 0b — shape before signatures, for both artifacts. Re-validated from their
    // serialized form so a hand-built artifact gets the same treatment as a parsed
    // one; see verifyManifestChain for the same argument.
    val cert = when (val c = parseEnrollmentCert(identityCertJson(identity.enrollmentCert))) {
        is EnrollmentParse.Err -> return IdentityChain.InvalidCertShape(c.reason)
        is EnrollmentParse.Ok -> c.value
    }
    val token = when (
        val t = parseEnrollmentToken(identityCredentialJson(identity.enrollment))
    ) {
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
    if (!IDENTITY_HEX_64_RE.matches(sessionPubkey)) {
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
    return IdentityChain.CourseOk(
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

/** The CURRENT 2.1 institution-scoped walk. See `Institution.kt`. */
private fun walkInstitutionChain(
    identity: SessionIdentity,
    sessionPubkey: String,
    anchor: InstitutionCert?,
    sessionStartedAt: String,
): IdentityChain {
    if (anchor == null) return IdentityChain.MissingTrustAnchor("institution_cert")

    // Step 0b — shape before signatures, for both artifacts.
    val cert = when (val c = parseInstitutionCert(identityCertJson(identity.enrollmentCert))) {
        is EnrollmentParse.Err -> return IdentityChain.InvalidCertShape(c.reason)
        is EnrollmentParse.Ok -> c.value
    }
    val credential = when (
        val t = parseStudentCredential(identityCredentialJson(identity.enrollment))
    ) {
        is EnrollmentParse.Err -> return IdentityChain.InvalidTokenShape(t.reason)
        is EnrollmentParse.Ok -> t.value
    }

    // Step 1 — the credential verifies against the key the ROOT vouched for.
    //
    // Deliberately the ANCHOR's institutionPubkey, never the travelling cert's. Step 2
    // forces the two to be equal anyway, but reading the key from the
    // already-root-verified value means a swapped travelling cert can never introduce
    // a key of the attacker's choosing, whatever happens downstream.
    if (!verifyStudentCredential(credential, anchor.institutionPubkey)) {
        return IdentityChain.InvalidInstitutionSignature
    }

    // Step 2 — THE INSTITUTION ANCHOR CHECK. The replacement for 2.0's course_id
    // triple comparison, and mandatory for exactly the same reason: root certifies
    // many institutions, so a genuine signature by a genuinely certified key proves
    // only WHO signed, never WHOM they were entitled to speak for. Comparing the id at
    // every link is what makes replaying one signer's credential under another's
    // authority impossible rather than merely unlikely.
    val pubkeyMismatch = cert.institutionPubkey != anchor.institutionPubkey
    if (credential.institutionId != cert.institutionId ||
        cert.institutionId != anchor.institutionId ||
        pubkeyMismatch
    ) {
        return IdentityChain.InstitutionMismatch(
            credentialInstitutionId = credential.institutionId,
            certInstitutionId = cert.institutionId,
            anchorInstitutionId = anchor.institutionId,
            pubkeyMismatch = pubkeyMismatch,
        )
    }

    // Step 3 — the student key adopted THIS session key.
    if (!IDENTITY_HEX_64_RE.matches(sessionPubkey)) {
        return IdentityChain.InvalidSessionPubkey
    }
    val binding = StudentSessionBinding(
        institutionId = credential.institutionId,
        studentRef = credential.studentRef,
        sessionPubkey = sessionPubkey,
    )
    if (!verifyStudentSessionBinding(binding, identity.sessionPubkeySig, credential.studentPubkey)) {
        return IdentityChain.InvalidSessionPubkeySignature
    }

    // Step 4 — non-fatal windows, each against its own relevant issue time.
    return IdentityChain.InstitutionOk(
        institutionId = credential.institutionId,
        studentRef = credential.studentRef,
        studentPubkey = credential.studentPubkey,
        institutionPubkey = anchor.institutionPubkey,
        cert = cert,
        credential = credential,
        certWindow = checkInstitutionCertWindow(cert, credential.issuedAt),
        tokenWindow = checkCredentialWindow(credential, sessionStartedAt),
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
 * Serialize the identity block for `session.start` (program spec §5), at EITHER
 * version.
 *
 * The two slots are serialized through [identityCertJson] / [identityCredentialJson],
 * which dispatch on the sealed artifact type. The wire key names are the same in both
 * versions — see [SessionIdentity] for why they were deliberately not renamed for 2.1.
 */
fun SessionIdentity.toJsonObject(): JsonObject = buildJsonObject {
    put("enrollment", identityCredentialJson(enrollment))
    put("enrollment_cert", identityCertJson(enrollmentCert))
    put("session_pubkey_sig", sessionPubkeySig)
}
