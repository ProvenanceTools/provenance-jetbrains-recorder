package dev.provenance.recorder.identity

import dev.provenance.core.IdentityChain
import dev.provenance.core.MANIFEST_FORMAT_VERSION_2
import dev.provenance.core.Manifest
import dev.provenance.core.SessionIdentity
import dev.provenance.core.SessionPubkeyBinding
import dev.provenance.core.StudentSessionBinding
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.core.deriveStudentKeypair
import dev.provenance.core.manifestFormatVersion
import dev.provenance.core.signSessionPubkey
import dev.provenance.core.signStudentSessionBinding
import dev.provenance.core.verifyIdentityChain
import dev.provenance.core.verifyInstitutionCert

/**
 * Build the `session.start.identity` block (program spec §5, §S2).
 *
 * This is the ONE place in the plugin where the student's private key is used. It derives
 * that key from the master secret, countersigns this session's ephemeral `session_pubkey`,
 * and assembles `{ enrollment, enrollment_cert, session_pubkey_sig }`.
 *
 * ## Two identity families, and which one wins
 *
 * - **2.1, INSTITUTION-scoped (current).** Anchored to the recorder's embedded ROOT public
 *   key, using the student's single GLOBAL key. Does not consult the manifest at all, so it
 *   works in any workspace — including one whose manifest is 1.x.
 * - **2.0, COURSE-scoped (legacy).** Anchored to the manifest's root-verified `course_cert`,
 *   using a per-course derived key. Kept forever: a token a student already holds must keep
 *   working.
 *
 * **If a 2.1 credential is stored it decides, with no fallback to 2.0.** The two families
 * attribute to different `student_ref`s — 2.0's is per-course, 2.1's is global — so quietly
 * falling back would file the session under a different contributor than the student
 * believes, and would hide the 2.1 problem that caused it. An integrity tool must not
 * silently change who it says did the work. Not blocking recording is preserved either way:
 * a failed 2.1 path skips the identity block, exactly as every other failure here does.
 *
 * ## Two rules, in priority order
 *
 * **1. Never block recording.** Every failure below returns [IdentityOutcome.Skipped] and
 * the session records without an `identity`. Same reasoning program spec §4 applies to an
 * expired `course_cert`: for an integrity tool, silently not recording is a worse failure
 * than recording under an incomplete credential. A student who has not enrolled yet, whose
 * keyring is unavailable, or whose course let a cert lapse still produces a bundle with a
 * full, chain-verifiable event stream.
 *
 * **2. Never emit an identity that does not verify.** Before returning, the assembled block
 * is walked with [verifyIdentityChain] against an ALREADY-VERIFIED anchor — the manifest's
 * `course_cert` at 2.0, or the root-verified `institution_cert` at 2.1 — which is the same
 * walk the analyzer will perform. A block that fails is dropped
 * rather than written, because `session.start` is signed and hash-chained: a broken claim in
 * there is permanent, unrepairable, and looks exactly like tampering during an adjudication.
 *
 * ## No network. Ever.
 *
 * Recorder PRD NG2. Nothing here fetches. The credential arrives by paste (see the
 * enrollment actions) and everything else is derived locally, so the whole identity path
 * works on a plane.
 */

/** Why no `identity` was emitted. Diagnostic only — none of these stop recording. */
sealed interface IdentitySkipReason {
    /**
     * 2.1: the recorder has no usable embedded ROOT public key, so the institution cert
     * cannot be turned into a trust anchor. A build/packaging problem, not a student one.
     */
    data object NoRootPublicKey : IdentitySkipReason

    /**
     * 2.1: the stored `institution_cert` does not verify against the recorder's embedded
     * ROOT key. The cert is not authentic, so nothing under it can be trusted — and an
     * unverifiable anchor must never be passed to the chain walk, because an attacker who
     * supplies the anchor supplies its public key too.
     */
    data object InstitutionCertNotRootSigned : IdentitySkipReason

    /**
     * 2.1: the credential names a `student_pubkey` this master secret does not derive.
     * The 2.1 analogue of [StudentKeyMismatch].
     */
    data class CredentialKeyMismatch(
        val credentialStudentPubkey: String,
        val derivedPubkey: String,
    ) : IdentitySkipReason

    /** A 1.x manifest, or a 2.0 one missing `course_id`/`course_cert`. Nothing to anchor to. */
    data object ManifestNot20 : IdentitySkipReason

    /** No token stored for this manifest's course. The ordinary pre-enrollment state. */
    data class NotEnrolled(val courseId: String) : IdentitySkipReason

    /** No usable master secret — absent, corrupt, or an unavailable keyring. */
    data class MasterSecretUnavailable(val reason: String) : IdentitySkipReason

    /** The session key this recorder generated is not 64-char hex. */
    data object InvalidSessionPubkey : IdentitySkipReason

    /**
     * The stored token names a `student_pubkey` this master secret does not derive —
     * normally a token minted before the student moved machines and imported a different
     * secret. Signing anyway would produce a countersignature that cannot verify.
     */
    data class StudentKeyMismatch(
        val tokenStudentPubkey: String,
        val derivedPubkey: String,
    ) : IdentitySkipReason

    /** The assembled block failed the chain walk. Rule 2: drop it. */
    data class ChainDidNotVerify(val error: IdentityChain.Err) : IdentitySkipReason

    /** Any unexpected throw. Recording continues regardless. */
    data class UnexpectedError(val reason: String) : IdentitySkipReason
}

sealed interface IdentityOutcome {
    data class Emitted(
        val identity: SessionIdentity,
        /**
         * The chain-walk result. Its `certWindow` / `tokenWindow` are NON-FATAL and may
         * report out-of-window; callers use them for a student-facing nudge, never as a
         * reason to withhold the block.
         */
        val verified: IdentityChain.Ok,
    ) : IdentityOutcome

    data class Skipped(val reason: IdentitySkipReason) : IdentityOutcome
}

private val IDENTITY_HEX_64_RE = Regex("^[0-9a-f]{64}$")

/**
 * @param manifest         The ALREADY-VERIFIED manifest for this assignment root. Its
 *                         `courseCert` is the trust anchor for the chain walk, so passing an
 *                         unverified manifest makes the verification meaningless (see
 *                         [verifyIdentityChain]).
 * @param sessionPubkeyHex This session's ephemeral public key, 64-char hex.
 * @param sessionStartedAt ISO 8601 session start. The token's validity window is judged
 *                         against this, never wall-clock now, so an archived bundle still
 *                         reads correctly years later.
 * @param secrets          The PasswordSafe-backed store in production.
 * @param keyCache         Optional derived-key cache ([CourseKeyCache]). When null the key is
 *                         derived directly, which is always correct — the cache is a
 *                         performance detail, never a correctness one, and a cache miss and a
 *                         cache absence must produce byte-identical keys.
 */
fun buildSessionIdentity(
    manifest: Manifest,
    sessionPubkeyHex: String,
    sessionStartedAt: String,
    secrets: SecretStore,
    keyCache: CourseKeyCache? = null,
    rootPubkeyHex: String? = null,
): IdentityOutcome {
    try {
        if (!IDENTITY_HEX_64_RE.matches(sessionPubkeyHex)) {
            return IdentityOutcome.Skipped(IdentitySkipReason.InvalidSessionPubkey)
        }

        // --- PRECEDENCE: 2.1 first, and if a 2.1 credential is stored it DECIDES.
        //
        // A student holding both a 2.1 credential and a legacy 2.0 token gets 2.1, and if
        // the 2.1 path then fails there is deliberately NO fallback to 2.0. Falling back
        // would be the more forgiving choice and it is the wrong one: the two families
        // attribute to DIFFERENT refs, so a silent fallback would file this session under
        // a different contributor than the student believes they are recording as, and the
        // 2.1 problem that caused it would never surface.
        val credential = loadStudentCredential(secrets)
        if (credential != null) {
            return buildInstitutionIdentity(
                stored = credential,
                sessionPubkeyHex = sessionPubkeyHex,
                sessionStartedAt = sessionStartedAt,
                secrets = secrets,
                keyCache = keyCache,
                rootPubkeyHex = rootPubkeyHex,
            )
        }

        // --- 2.0, LEGACY. Reached only when no 2.1 credential is stored.
        // --- Anchor. There is no 2.0 identity chain without a course cert to anchor it.
        val courseCert = manifest.courseCert
        val courseId = manifest.courseId
        if (manifestFormatVersion(manifest) != MANIFEST_FORMAT_VERSION_2 ||
            courseCert == null ||
            courseId.isNullOrEmpty()
        ) {
            return IdentityOutcome.Skipped(IdentitySkipReason.ManifestNot20)
        }

        // --- The token for THIS course. Keyed by the manifest's course_id, so an enrollment
        // --- in another course is simply "not enrolled" here; the chain walk's step 3 would
        // --- reject it anyway.
        val stored = loadEnrollment(secrets, courseId)
            ?: return IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled(courseId))

        // --- The student key. LOADED, never created: a freshly generated secret could not
        // --- possibly derive the key an existing token names, so creating one here would
        // --- only manufacture a mismatch.
        val master = when (val m = loadMasterSecret(secrets)) {
            is StoreResult.Ok -> m.value
            is StoreResult.Err ->
                return IdentityOutcome.Skipped(
                    IdentitySkipReason.MasterSecretUnavailable(m.error::class.simpleName ?: "unknown"),
                )
        }

        // Via the cache when one is supplied, else derived directly. Both paths MUST yield the
        // same key: the cache is keyed on a fingerprint of this master secret, so a stale entry
        // from a previously-imported secret can never be returned here.
        val derived = if (keyCache != null) {
            keyCache.get(master, courseId)
                ?: return IdentityOutcome.Skipped(
                    IdentitySkipReason.MasterSecretUnavailable("derive_failed"),
                )
        } else {
            deriveCourseKeypair(master, courseId)
        }

        if (derived.publicKeyHex != stored.enrollment.studentPubkey) {
            return IdentityOutcome.Skipped(
                IdentitySkipReason.StudentKeyMismatch(
                    tokenStudentPubkey = stored.enrollment.studentPubkey,
                    derivedPubkey = derived.publicKeyHex,
                ),
            )
        }

        // --- Countersign this session's ephemeral key. `student_ref` and `course_id` come
        // --- from the token, so the signature asserts which student, in which course,
        // --- adopted this key — and the verifier cross-checks both.
        val sessionPubkeySig = signSessionPubkey(
            SessionPubkeyBinding(
                courseId = stored.enrollment.courseId,
                studentRef = stored.enrollment.studentRef,
                sessionPubkey = sessionPubkeyHex,
            ),
            derived.privateKey,
        )

        val identity = SessionIdentity(
            enrollment = stored.enrollment,
            enrollmentCert = stored.enrollmentCert,
            sessionPubkeySig = sessionPubkeySig,
        )

        // --- Rule 2. Walk it exactly as the analyzer will, BEFORE it becomes part of a
        // --- signed chain we can never amend.
        return when (
            val walked = verifyIdentityChain(
                identity = identity,
                sessionPubkey = sessionPubkeyHex,
                courseCert = courseCert,
                sessionStartedAt = sessionStartedAt,
            )
        ) {
            // Out-of-window is deliberately NOT a reason to withhold: expiry is reported,
            // never enforced (program spec §4). `walked.certWindow` / `tokenWindow` carry it on.
            is IdentityChain.Ok -> IdentityOutcome.Emitted(identity, walked)
            is IdentityChain.Err ->
                IdentityOutcome.Skipped(IdentitySkipReason.ChainDidNotVerify(walked))
        }
    } catch (e: Throwable) {
        // Widened to Throwable on purpose: this runs during session construction, and an
        // identity problem — including a NoClassDefFoundError from an unavailable credential
        // backend — must never be the reason a student's work goes unrecorded.
        return IdentityOutcome.Skipped(
            IdentitySkipReason.UnexpectedError(e.message ?: e.toString()),
        )
    }
}

// ---------------------------------------------------------------------------
// The 2.1 INSTITUTION-scoped path
// ---------------------------------------------------------------------------

/**
 * Build the identity block from a 2.1 credential.
 *
 * Structurally the twin of the 2.0 path above, with three differences that all follow from
 * identity no longer being course-scoped:
 *
 *  - **The manifest is not consulted at all.** A 2.1 credential names no course, so there
 *    is nothing to match against `manifest.courseId`, and the trust anchor is the
 *    recorder's embedded ROOT key rather than the manifest's `course_cert`. A student with
 *    a 2.1 credential therefore gets an identity even in a 1.x workspace — which is the
 *    point: the 2.0 design could not produce an identity before the first submission.
 *  - **The student key is the single GLOBAL one** ([deriveStudentKeypair]), not a
 *    per-course derivation.
 *  - **The anchor is root-verified HERE, by us.** [verifyIdentityChain] does not do it,
 *    deliberately — it takes the anchor as a parameter exactly as the 2.0 walk takes an
 *    already-verified `course_cert`. Passing the stored cert unverified would make the
 *    entire walk meaningless, because whoever supplies the cert supplies its
 *    `institution_pubkey` too.
 */
private fun buildInstitutionIdentity(
    stored: StoredCredential,
    sessionPubkeyHex: String,
    sessionStartedAt: String,
    secrets: SecretStore,
    keyCache: CourseKeyCache?,
    rootPubkeyHex: String?,
): IdentityOutcome {
    if (rootPubkeyHex == null || !IDENTITY_HEX_64_RE.matches(rootPubkeyHex)) {
        return IdentityOutcome.Skipped(IdentitySkipReason.NoRootPublicKey)
    }

    // --- Turn the travelling cert into a TRUST ANCHOR, or refuse to proceed.
    if (!verifyInstitutionCert(stored.enrollmentCert, rootPubkeyHex)) {
        return IdentityOutcome.Skipped(IdentitySkipReason.InstitutionCertNotRootSigned)
    }

    // --- The student key. LOADED, never created: a freshly generated secret could not
    // --- possibly derive the key an existing credential names.
    val master = when (val m = loadMasterSecret(secrets)) {
        is StoreResult.Ok -> m.value
        is StoreResult.Err ->
            return IdentityOutcome.Skipped(
                IdentitySkipReason.MasterSecretUnavailable(m.error::class.simpleName ?: "unknown"),
            )
    }

    // Via the cache when one is supplied, else derived directly. Both paths MUST yield the
    // same key: the cache is keyed on a fingerprint of this master secret, so a stale entry
    // from a previously-imported secret can never be returned here.
    val derived = if (keyCache != null) {
        keyCache.getGlobal(master)
            ?: return IdentityOutcome.Skipped(
                IdentitySkipReason.MasterSecretUnavailable("derive_failed"),
            )
    } else {
        deriveStudentKeypair(master)
    }

    if (derived.publicKeyHex != stored.enrollment.studentPubkey) {
        return IdentityOutcome.Skipped(
            IdentitySkipReason.CredentialKeyMismatch(
                credentialStudentPubkey = stored.enrollment.studentPubkey,
                derivedPubkey = derived.publicKeyHex,
            ),
        )
    }

    // --- Countersign this session's ephemeral key under the v2 binding payload. Its
    // --- `purpose` tag differs from 2.0's, so a countersignature can never be replayed
    // --- across versions.
    val sessionPubkeySig = signStudentSessionBinding(
        StudentSessionBinding(
            institutionId = stored.enrollment.institutionId,
            studentRef = stored.enrollment.studentRef,
            sessionPubkey = sessionPubkeyHex,
        ),
        derived.privateKey,
    )

    val identity = SessionIdentity(
        enrollment = stored.enrollment,
        enrollmentCert = stored.enrollmentCert,
        sessionPubkeySig = sessionPubkeySig,
    )

    // --- Rule 2. Walk it exactly as the analyzer will, BEFORE it becomes part of a signed
    // --- chain we can never amend.
    return when (
        val walked = verifyIdentityChain(
            identity = identity,
            sessionPubkey = sessionPubkeyHex,
            courseCert = null,
            sessionStartedAt = sessionStartedAt,
            institutionCert = stored.enrollmentCert,
        )
    ) {
        // Out-of-window is deliberately NOT a reason to withhold: expiry is reported, never
        // enforced (program spec §4). `walked.certWindow` / `tokenWindow` carry it on.
        is IdentityChain.Ok -> IdentityOutcome.Emitted(identity, walked)
        is IdentityChain.Err ->
            IdentityOutcome.Skipped(IdentitySkipReason.ChainDidNotVerify(walked))
    }
}
