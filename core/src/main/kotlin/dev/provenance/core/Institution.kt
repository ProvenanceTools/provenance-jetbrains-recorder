package dev.provenance.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Institution certificate + student credential — the INSTITUTION-SCOPED identity
 * chain, at identity `format_version` 2.1. The Kotlin twin of log-core's
 * `institution.ts`.
 *
 * Read `Enrollment.kt` first: this file is its deliberate structural parallel, and
 * that file remains live FOREVER for archived bundles.
 *
 * ## Why identity stopped being course-scoped
 *
 * The 2.0 chain bound a student to a COURSE: a per-course derived key, a
 * course-signed enrollment cert, a token naming a `course_id`. Minting that token
 * required a roster match, and rosters are populated by the Gradescope ingest path,
 * which only runs AFTER a student submits. So a student could not obtain an identity
 * until after their first submission — but their sessions need an identity BEFORE
 * they work, or the work carries none. That is a deadlock, and course-scoping is
 * what creates it.
 *
 * ## The chain
 *
 * ```
 *   root keypair              (offline, maintainer-held; signs course certs
 *        │ signs               AND institution certs — nothing else)
 *        ▼
 *   institution_cert          { format_version, institution_id,
 *        │ authorizes           institution_pubkey, valid_from, valid_until }
 *        ▼                      + root_sig
 *   institution keypair       ◄── LIVES ON THE SERVER. The only private key that
 *        │ signs                  does. Signs student credentials, nothing else.
 *        ▼
 *   student_credential        { format_version, institution_id, student_ref,
 *        │ authorizes           student_pubkey, issued_at, expires_at }
 *        ▼                      + institution_sig
 *   student keypair           (ONE per student, forever, across every course;
 *        │ countersigns         derived on the student's machine — StudentKeys.kt)
 *        ▼
 *   session_pubkey            (the existing ephemeral session key)
 * ```
 *
 * ONE delegation from root, not two. The 2.0 chain needed the extra
 * `course_cert → enrollment_cert` hop purely because the course key is offline and
 * cannot mint per-student tokens on demand. The institution key is certified by root
 * directly and lives on the server, so that hop has nothing left to do.
 *
 * **Course keys are unaffected.** They keep signing manifests and capture policy
 * exactly as before. The identity chain deliberately does NOT anchor to whichever
 * `course_cert` a manifest carries.
 *
 * ## The invariant that replaces the cross-course forgery check
 *
 * Root legitimately certifies MANY institutions. An attacker holding a genuinely
 * root-certified institution key for `stanford` can mint a credential whose
 * `institution_id` says `berkeley`, ship it with their own (genuine, root-signed)
 * `stanford` cert, and every signature verifies. What stops it is asserting that
 *
 *     credential.institutionId == cert.institutionId == anchor.institutionId
 *
 * and that the cert travelling in the bundle names the SAME public key as the
 * root-verified anchor. One signer's credential can then never be replayed under
 * another signer's authority. This is a MANDATORY conformance vector
 * (`cross_institution_forgery` in `identity.json`), exactly as `cross_course_forgery`
 * is for 2.0.
 *
 * ## `student_ref` is global, opaque, and always a VALUE
 *
 * An opaque UUID — never a raw SID, name, or email. GLOBAL rather than per-course:
 * one student, one ref, one credential, every course. In a shared repo one partner
 * can read the other's `session.start` and must see only a UUID.
 *
 * It is also never an object KEY in a signed payload — the permanent constraint
 * documented in `CourseCert.kt`. Every key in every payload below is a fixed ASCII
 * identifier chosen upstream, and for the same cross-port reason there are no JSON
 * arrays anywhere in a signed payload.
 *
 * ## Expiry is reported, never enforced
 *
 * Unchanged from 2.0. An out-of-window credential is NOT an error; it is returned on
 * the success value for the caller to act on. An institution letting a cert lapse
 * mid-semester must not silently stop recording — for an integrity tool that is a
 * worse failure than recording under a stale credential. And every window is judged
 * against the RELEVANT ISSUE TIME, never wall-clock now, so an archived bundle still
 * verifies years later:
 *
 *  - the institution cert's window is checked against the CREDENTIAL's `issuedAt`;
 *  - the credential's window is checked against the SESSION start.
 *
 * ## Revocation
 *
 * Not modelled, for the same reason as `course_cert`: an offline recorder cannot
 * learn about it without a network call, which recorder PRD NG2 forbids. The offline
 * mitigation is short windows.
 */

// ---------------------------------------------------------------------------
// Constants — the cross-language contract
// ---------------------------------------------------------------------------

/**
 * The identity `format_version` at which identity is INSTITUTION-scoped.
 *
 * This value is the DISCRIMINATOR the identity chain routes on. It lives inside both
 * signed payloads, so it cannot be flipped without invalidating a signature, and
 * [verifyIdentityChain] reads it before doing any signature work.
 *
 * Routing on a signed version — rather than on which fields happen to be present —
 * is not a stylistic preference. This project already shipped the other bug once:
 * a reader treated the mere PRESENCE of an embedded manifest as a 2.0 claim, and
 * that made the entire legacy path unreachable.
 */
const val INSTITUTION_IDENTITY_FORMAT_VERSION: String = "2.1"

/**
 * Domain-separation tag for the 2.1 session-pubkey countersignature.
 *
 * Deliberately a DIFFERENT string from the 2.0 tag ([SESSION_PUBKEY_BINDING_PURPOSE]).
 * The two binding payloads already differ structurally (`institution_id` vs
 * `course_id`), so their bytes could never collide — but a distinct tag makes the
 * separation an explicit property of the protocol rather than an accident of field
 * naming, and leaves the 2.0 tag meaning exactly one thing forever.
 */
const val STUDENT_SESSION_BINDING_PURPOSE: String = "provenance-session-pubkey-binding-v2"

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * A ROOT-signed statement that an INSTITUTION key may issue student credentials.
 *
 * Structurally the institution-side twin of `course_cert`: same issuer (root), same
 * window semantics, same "travels beside the thing it authorizes, outside the payload
 * that signs it" distribution rule. It rides inline in the `session.start` identity
 * block so a bundle is self-contained — an analyzer adjudicating in 2031 has the
 * whole chain in the file and fetches nothing.
 */
data class InstitutionCert(
    /** Must be [INSTITUTION_IDENTITY_FORMAT_VERSION]. Inside the signed payload. */
    override val formatVersion: String,
    /**
     * The institution this key speaks for. MUST equal the credential's
     * `institutionId` and the root-verified anchor's — see the file docstring.
     */
    val institutionId: String,
    /** Hex ed25519 public key of the server-held institution signing key, 64 chars. */
    val institutionPubkey: String,
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
    /** Hex ed25519 signature by the ROOT key, 128 chars (64 bytes). */
    val rootSig: String,
) : IdentityCert

/**
 * An institution-signed statement that a student public key belongs to a particular
 * person at that institution.
 *
 * The whole of a student's identity, obtained ONCE. It names no course, no
 * assignment, and no semester — deliberately. Course membership is a roster question
 * the server answers later against data it owns; making it a precondition of HAVING
 * an identity is what deadlocked the 2.0 design.
 */
data class StudentCredential(
    /** Must be [INSTITUTION_IDENTITY_FORMAT_VERSION]. Inside the signed payload. */
    override val formatVersion: String,
    /** The institution whose key issued this. */
    val institutionId: String,
    /**
     * Opaque GLOBAL roster reference. Never a student ID number, name, or email, and
     * never an object key. One per student, not one per course.
     */
    val studentRef: String,
    /** Hex ed25519 public key of the student's single long-lived key, 64 chars. */
    val studentPubkey: String,
    /** ISO 8601. Also the instant the institution cert's window is judged against. */
    val issuedAt: String,
    /** ISO 8601. Date-only resolves through the end of that day. */
    val expiresAt: String,
    /** Hex ed25519 signature by the INSTITUTION key, 128 chars (64 bytes). */
    val institutionSig: String,
) : IdentityCredential

/** The three fields the student's single global key countersigns, at 2.1. */
data class StudentSessionBinding(
    val institutionId: String,
    val studentRef: String,
    val sessionPubkey: String,
)

// ---------------------------------------------------------------------------
// Signed payloads — the exact bytes three ports must reproduce
// ---------------------------------------------------------------------------

/**
 * Build the canonical UTF-8 bytes the ROOT key signs for an institution cert.
 *
 * `root_sig` is excluded; the five remaining fields are canonicalized. JCS orders
 * keys, so the literal order below is irrelevant to the output — the resulting key
 * order is always:
 *
 *   format_version, institution_id, institution_pubkey, valid_from, valid_until
 */
fun buildInstitutionCertSignedPayload(cert: InstitutionCert): ByteArray {
    val payload = buildJsonObject {
        put("format_version", cert.formatVersion)
        put("institution_id", cert.institutionId)
        put("institution_pubkey", cert.institutionPubkey)
        put("valid_from", cert.validFrom)
        put("valid_until", cert.validUntil)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Build the canonical UTF-8 bytes the INSTITUTION key signs for a student credential.
 *
 * `institution_sig` is excluded; the six remaining fields are canonicalized. The
 * resulting JCS key order is always:
 *
 *   expires_at, format_version, institution_id, issued_at, student_pubkey, student_ref
 *
 * Note `student_ref` appears as a VALUE at a fixed ASCII key — never promoted to a
 * key itself. See the file docstring.
 */
fun buildStudentCredentialSignedPayload(credential: StudentCredential): ByteArray {
    val payload = buildJsonObject {
        put("expires_at", credential.expiresAt)
        put("format_version", credential.formatVersion)
        put("institution_id", credential.institutionId)
        put("issued_at", credential.issuedAt)
        put("student_pubkey", credential.studentPubkey)
        put("student_ref", credential.studentRef)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Build the canonical UTF-8 bytes the STUDENT key signs to bind an ephemeral
 * `session_pubkey` to itself.
 *
 * A bare 64-char hex string would have been the minimal thing to sign. It is not what
 * is signed, for the same two reasons as at 2.0:
 *
 *  - **Domain separation.** A signature over an unstructured blob is a signature over
 *    anything that blob might also mean. The fixed `purpose` tag makes this message
 *    unmistakably this message.
 *  - **Self-describing binding.** Including `institution_id` and `student_ref` means
 *    the countersignature itself asserts WHICH student, at WHICH institution, adopted
 *    this session key, rather than taking either on trust from elsewhere.
 *
 * JCS key order is always: institution_id, purpose, session_pubkey, student_ref.
 */
fun buildStudentSessionBindingPayload(binding: StudentSessionBinding): ByteArray {
    val payload = buildJsonObject {
        put("institution_id", binding.institutionId)
        put("purpose", STUDENT_SESSION_BINDING_PURPOSE)
        put("session_pubkey", binding.sessionPubkey)
        put("student_ref", binding.studentRef)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

// ---------------------------------------------------------------------------
// Shape validation — always before signature work
// ---------------------------------------------------------------------------

/**
 * Validate the shape of an already-JSON-parsed institution cert.
 *
 * Takes a [JsonElement] rather than text because the cert travels inline inside a
 * `session.start` payload. Unknown keys are ignored for forward compatibility, which
 * is safe: canonicalization operates on the five named fields only, so an unknown key
 * cannot silently change the signed bytes.
 *
 * This does NOT check `format_version` against the expected constant —
 * [verifyIdentityChain] gates on that first and reports it as a distinct error, so a
 * version problem is never mistaken for a malformed artifact.
 */
fun parseInstitutionCert(value: JsonElement?): EnrollmentParse<InstitutionCert> {
    val obj = value as? JsonObject
        ?: return EnrollmentParse.Err("invalid_shape: must be an object")

    val formatVersion = obj.requireString("format_version")
        ?: return EnrollmentParse.Err("invalid_shape: format_version must be a non-empty string")
    val institutionId = obj.requireString("institution_id")
        ?: return EnrollmentParse.Err("invalid_shape: institution_id must be a non-empty string")

    val bounds = when (val b = obj.requireOrderedBounds("valid_from", "valid_until")) {
        is EnrollmentParse.Err -> return b
        is EnrollmentParse.Ok -> b.value
    }

    val institutionPubkey = obj.requireHex("institution_pubkey", IDENTITY_HEX_64_RE)
        ?: return EnrollmentParse.Err("invalid_shape: institution_pubkey must be a 64-char hex string")
    val rootSig = obj.requireHex("root_sig", IDENTITY_HEX_128_RE)
        ?: return EnrollmentParse.Err("invalid_shape: root_sig must be a 128-char hex string")

    return EnrollmentParse.Ok(
        InstitutionCert(
            formatVersion = formatVersion,
            institutionId = institutionId,
            institutionPubkey = institutionPubkey,
            validFrom = bounds.first,
            validUntil = bounds.second,
            rootSig = rootSig,
        ),
    )
}

/**
 * Validate the shape of an already-JSON-parsed student credential. Unknown keys are
 * ignored, for the same reason as [parseInstitutionCert].
 */
fun parseStudentCredential(value: JsonElement?): EnrollmentParse<StudentCredential> {
    val obj = value as? JsonObject
        ?: return EnrollmentParse.Err("invalid_shape: must be an object")

    val formatVersion = obj.requireString("format_version")
        ?: return EnrollmentParse.Err("invalid_shape: format_version must be a non-empty string")
    val institutionId = obj.requireString("institution_id")
        ?: return EnrollmentParse.Err("invalid_shape: institution_id must be a non-empty string")
    val studentRef = obj.requireString("student_ref")
        ?: return EnrollmentParse.Err("invalid_shape: student_ref must be a non-empty string")

    val bounds = when (val b = obj.requireOrderedBounds("issued_at", "expires_at")) {
        is EnrollmentParse.Err -> return b
        is EnrollmentParse.Ok -> b.value
    }

    val studentPubkey = obj.requireHex("student_pubkey", IDENTITY_HEX_64_RE)
        ?: return EnrollmentParse.Err("invalid_shape: student_pubkey must be a 64-char hex string")
    val institutionSig = obj.requireHex("institution_sig", IDENTITY_HEX_128_RE)
        ?: return EnrollmentParse.Err("invalid_shape: institution_sig must be a 128-char hex string")

    return EnrollmentParse.Ok(
        StudentCredential(
            formatVersion = formatVersion,
            institutionId = institutionId,
            studentRef = studentRef,
            studentPubkey = studentPubkey,
            issuedAt = bounds.first,
            expiresAt = bounds.second,
            institutionSig = institutionSig,
        ),
    )
}

// ---------------------------------------------------------------------------
// Signing — maintainer/server tooling and the conformance-vector generator only.
// A recorder never calls the first two; it only ever verifies.
// ---------------------------------------------------------------------------

/** Sign an institution cert with the ROOT private key (offline operation). */
fun signInstitutionCert(cert: InstitutionCert, rootPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildInstitutionCertSignedPayload(cert), rootPrivkey32))

/** Sign a student credential with the INSTITUTION private key (server-side). */
fun signStudentCredential(credential: StudentCredential, institutionPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(
        Ed25519.sign(buildStudentCredentialSignedPayload(credential), institutionPrivkey32),
    )

/**
 * Countersign a session public key with the student's single long-lived private key.
 * Called by the recorder at session start — the one signing operation on this path
 * that happens on the student's machine.
 */
fun signStudentSessionBinding(binding: StudentSessionBinding, studentPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildStudentSessionBindingPayload(binding), studentPrivkey32))

// ---------------------------------------------------------------------------
// Single-link verification
// ---------------------------------------------------------------------------

/**
 * Verify an institution cert against the ROOT public key.
 *
 * This is the call that turns a cert travelling in a student-editable bundle into a
 * TRUST ANCHOR. [verifyIdentityChain] does NOT do it — exactly as it does not
 * re-verify `course_cert`, and exactly as [verifyCourseCert] takes the root key as a
 * parameter rather than knowing one. The caller performs it and passes the result in;
 * skipping it makes the whole chain meaningless, because an attacker who supplies the
 * cert supplies `institution_pubkey` too.
 *
 * @param rootPubkeyHex The recorder's embedded `ROOT_PUBLIC_KEY_HEX`, or the
 *                      analyzer's configured root key. NEVER read from the bundle.
 */
fun verifyInstitutionCert(cert: InstitutionCert, rootPubkeyHex: String): Boolean =
    verifyDetached(buildInstitutionCertSignedPayload(cert), cert.rootSig, rootPubkeyHex)

/**
 * Verify a student credential against the institution public key the root certified.
 *
 * @param institutionPubkeyHex MUST come from an already-root-verified
 *                             `institution_cert`. Reading it from the credential's own
 *                             travelling companion makes this check circular.
 */
fun verifyStudentCredential(credential: StudentCredential, institutionPubkeyHex: String): Boolean =
    verifyDetached(
        buildStudentCredentialSignedPayload(credential),
        credential.institutionSig,
        institutionPubkeyHex,
    )

/**
 * Verify that the student key named by a credential countersigned this session's
 * ephemeral public key.
 */
fun verifyStudentSessionBinding(
    binding: StudentSessionBinding,
    sigHex: String,
    studentPubkeyHex: String,
): Boolean = verifyDetached(buildStudentSessionBindingPayload(binding), sigHex, studentPubkeyHex)

// ---------------------------------------------------------------------------
// Window checks — non-fatal, never against wall-clock now
// ---------------------------------------------------------------------------

/**
 * Was [credential] in window at [at]?
 *
 * [at] is the SESSION start time, not wall-clock now: a Fall 2026 session must still
 * read as in-window in 2031. Reuses [CertWindowStatus], so one status vocabulary
 * covers every window in the system.
 */
fun checkCredentialWindow(credential: StudentCredential, at: String): CertWindowStatus =
    checkWindow(credential.issuedAt, credential.expiresAt, at)

/**
 * Was [cert] in window at [at]? [at] is the CREDENTIAL's `issuedAt` when called from
 * the identity chain — "was this institution key authorized when it issued that
 * credential".
 */
fun checkInstitutionCertWindow(cert: InstitutionCert, at: String): CertWindowStatus =
    checkWindow(cert.validFrom, cert.validUntil, at)

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/**
 * Serialize an institution cert to its on-wire JSON shape. Emits all six fields,
 * `root_sig` included: unlike [buildInstitutionCertSignedPayload] this is transport,
 * not the signed payload.
 */
fun InstitutionCert.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("institution_id", institutionId)
    put("institution_pubkey", institutionPubkey)
    put("valid_from", validFrom)
    put("valid_until", validUntil)
    put("root_sig", rootSig)
}

/** Serialize a student credential to its on-wire JSON shape, `institution_sig` included. */
fun StudentCredential.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("institution_id", institutionId)
    put("student_ref", studentRef)
    put("student_pubkey", studentPubkey)
    put("issued_at", issuedAt)
    put("expires_at", expiresAt)
    put("institution_sig", institutionSig)
}
