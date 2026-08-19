package dev.provenance.recorder.identity

import dev.provenance.core.IdentityChain
import dev.provenance.core.MANIFEST_FORMAT_VERSION_2
import dev.provenance.core.Manifest
import dev.provenance.core.SessionIdentity
import dev.provenance.core.SessionPubkeyBinding
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.core.manifestFormatVersion
import dev.provenance.core.signSessionPubkey
import dev.provenance.core.verifyIdentityChain

/**
 * Build the `session.start.identity` block (program spec §5, §S2).
 *
 * This is the ONE place in the plugin where the student's per-course private key is used.
 * It derives that key from the master secret, countersigns this session's ephemeral
 * `session_pubkey`, and assembles `{ enrollment, enrollment_cert, session_pubkey_sig }`.
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
 * is walked with [verifyIdentityChain] against the manifest's ALREADY-VERIFIED
 * `course_cert` — the same walk the analyzer will perform. A block that fails is dropped
 * rather than written, because `session.start` is signed and hash-chained: a broken claim in
 * there is permanent, unrepairable, and looks exactly like tampering during an adjudication.
 *
 * ## No network. Ever.
 *
 * Recorder PRD NG2. Nothing here fetches. The enrollment token arrives by paste (see the
 * enrollment actions) and everything else is derived locally, so the whole identity path
 * works on a plane.
 */

/** Why no `identity` was emitted. Diagnostic only — none of these stop recording. */
sealed interface IdentitySkipReason {
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
 */
fun buildSessionIdentity(
    manifest: Manifest,
    sessionPubkeyHex: String,
    sessionStartedAt: String,
    secrets: SecretStore,
): IdentityOutcome {
    try {
        // --- Anchor. There is no identity chain without a course cert to anchor it.
        val courseCert = manifest.courseCert
        val courseId = manifest.courseId
        if (manifestFormatVersion(manifest) != MANIFEST_FORMAT_VERSION_2 ||
            courseCert == null ||
            courseId.isNullOrEmpty()
        ) {
            return IdentityOutcome.Skipped(IdentitySkipReason.ManifestNot20)
        }

        if (!IDENTITY_HEX_64_RE.matches(sessionPubkeyHex)) {
            return IdentityOutcome.Skipped(IdentitySkipReason.InvalidSessionPubkey)
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

        val derived = deriveCourseKeypair(master, courseId)
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
