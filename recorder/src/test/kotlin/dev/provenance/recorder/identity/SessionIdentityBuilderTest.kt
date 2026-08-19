package dev.provenance.recorder.identity

import dev.provenance.core.CertWindowReason
import dev.provenance.core.CertWindowStatus
import dev.provenance.core.Ed25519
import dev.provenance.core.IdentityChain
import dev.provenance.core.SessionPubkeyBinding
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.core.verifyEnrollmentCert
import dev.provenance.core.verifySessionPubkeySig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules from program spec §5a, and the emitted shape.
 *
 * Every signature in these fixtures is real, so a chain-walk regression shows up as a
 * skipped identity rather than as a passing test over stubbed crypto.
 */
class SessionIdentityBuilderTest {

    private val sessionPubkey = "20".repeat(32)
    private val startedAt = "2026-09-08T12:00:00Z"

    private fun build(
        secrets: SecretStore,
        manifest: dev.provenance.core.Manifest = EnrollmentFixtures.manifest(),
        pubkey: String = sessionPubkey,
        at: String = startedAt,
    ) = buildSessionIdentity(manifest, pubkey, at, secrets)

    // -----------------------------------------------------------------------
    // The happy path and the emitted shape
    // -----------------------------------------------------------------------

    @Test
    fun `an enrolled student emits a chain-verified identity`() {
        val outcome = build(EnrollmentFixtures.enrolledStore())
        val emitted = outcome as IdentityOutcome.Emitted

        assertEquals(EnrollmentFixtures.COURSE_ID, emitted.verified.courseId)
        assertEquals(EnrollmentFixtures.STUDENT_REF, emitted.verified.studentRef)
        assertEquals(EnrollmentFixtures.studentPubkeyHex(), emitted.verified.studentPubkey)
        assertEquals(EnrollmentFixtures.enrollmentPubkeyHex, emitted.verified.enrollmentPubkey)
        assertTrue(emitted.verified.certWindow.inWindow)
        assertTrue(emitted.verified.tokenWindow.inWindow)
    }

    /**
     * EXACTLY three keys, with the two artifacts carrying exactly their contracted fields.
     * The shape is what an analyzer parses years from now, so it is pinned here rather than
     * left to whatever the data classes happen to serialize.
     */
    @Test
    fun `the emitted identity block has exactly the three contracted keys`() {
        val emitted = build(EnrollmentFixtures.enrolledStore()) as IdentityOutcome.Emitted
        val json = emitted.identity.toJsonObject()

        assertEquals(setOf("enrollment", "enrollment_cert", "session_pubkey_sig"), json.keys)
        assertEquals(
            setOf(
                "format_version", "student_ref", "course_id",
                "student_pubkey", "issued_at", "expires_at", "enrollment_sig",
            ),
            json["enrollment"]!!.jsonObject.keys,
        )
        assertEquals(
            setOf(
                "format_version", "course_id", "enrollment_pubkey",
                "valid_from", "valid_until", "course_sig",
            ),
            json["enrollment_cert"]!!.jsonObject.keys,
        )
        val sig = json["session_pubkey_sig"]!!.jsonPrimitive.content
        assertTrue(sig.matches(Regex("^[0-9a-f]{128}$")))
    }

    /**
     * The countersignature really binds THIS session key, under the student key the token
     * names — not merely a well-formed 128 hex characters.
     */
    @Test
    fun `the countersignature verifies against the token's student pubkey`() {
        val emitted = build(EnrollmentFixtures.enrolledStore()) as IdentityOutcome.Emitted
        assertTrue(
            verifySessionPubkeySig(
                SessionPubkeyBinding(
                    courseId = EnrollmentFixtures.COURSE_ID,
                    studentRef = EnrollmentFixtures.STUDENT_REF,
                    sessionPubkey = sessionPubkey,
                ),
                emitted.identity.sessionPubkeySig,
                EnrollmentFixtures.studentPubkeyHex(),
            ),
        )
        // ...and does NOT verify for a different session key: it is a binding, not a stamp.
        assertFalse(
            verifySessionPubkeySig(
                SessionPubkeyBinding(
                    courseId = EnrollmentFixtures.COURSE_ID,
                    studentRef = EnrollmentFixtures.STUDENT_REF,
                    sessionPubkey = "21".repeat(32),
                ),
                emitted.identity.sessionPubkeySig,
                EnrollmentFixtures.studentPubkeyHex(),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Rule 1: never block recording
    // -----------------------------------------------------------------------

    /**
     * Every one of these must omit the block and let the session record. Silently not
     * recording is a worse failure for an integrity tool than recording without an
     * identity — the same reasoning §4 applies to an expired course_cert.
     */
    @Test
    fun `every failure mode skips instead of throwing`() {
        // Not enrolled — the ordinary pre-enrollment state.
        val empty = FakeSecretStore()
        importMasterSecret(empty, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        val notEnrolled = build(empty) as IdentityOutcome.Skipped
        assertTrue(notEnrolled.reason is IdentitySkipReason.NotEnrolled)

        // Keyring unavailable.
        val broken = EnrollmentFixtures.enrolledStore()
        broken.failure = IllegalStateException("keyring locked")
        assertTrue(build(broken) is IdentityOutcome.Skipped)

        // A 1.x manifest has no course_cert to anchor to.
        val legacy = build(EnrollmentFixtures.enrolledStore(), manifest = EnrollmentFixtures.legacyManifest())
        assertTrue((legacy as IdentityOutcome.Skipped).reason is IdentitySkipReason.ManifestNot20)

        // A malformed session pubkey is reported distinctly.
        val badKey = build(EnrollmentFixtures.enrolledStore(), pubkey = "nope") as IdentityOutcome.Skipped
        assertTrue(badKey.reason is IdentitySkipReason.InvalidSessionPubkey)

        // No master secret at all: enrolled but nothing to sign with.
        val tokenOnly = FakeSecretStore()
        saveEnrollment(tokenOnly, EnrollmentFixtures.blobJson())
        val noMaster = build(tokenOnly) as IdentityOutcome.Skipped
        assertTrue(noMaster.reason is IdentitySkipReason.MasterSecretUnavailable)
    }

    /**
     * A token minted for a DIFFERENT master secret — the classic "moved machines and
     * imported the wrong secret" case. Signing anyway would produce a countersignature that
     * cannot verify, so it is caught before signing rather than after.
     */
    @Test
    fun `a token naming another master secret's key is skipped, not signed`() {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        // Token minted against a foreign master secret.
        val foreign = deriveCourseKeypair(ByteArray(32) { 0x77 }, EnrollmentFixtures.COURSE_ID)
        saveEnrollment(s, EnrollmentFixtures.blobJson(studentPubkey = foreign.publicKeyHex))

        val skipped = build(s) as IdentityOutcome.Skipped
        val reason = skipped.reason as IdentitySkipReason.StudentKeyMismatch
        assertEquals(foreign.publicKeyHex, reason.tokenStudentPubkey)
        assertEquals(EnrollmentFixtures.studentPubkeyHex(), reason.derivedPubkey)
    }

    /**
     * A master secret this student never had cannot be turned into an identity by generating
     * a new one. `loadMasterSecret`, not `loadOrCreate`, is used on this path precisely so a
     * fresh secret is never manufactured mid-session.
     */
    @Test
    fun `the session path never creates a master secret`() {
        val tokenOnly = FakeSecretStore()
        saveEnrollment(tokenOnly, EnrollmentFixtures.blobJson())
        build(tokenOnly)
        assertTrue(loadMasterSecret(tokenOnly) is StoreResult.Err)
    }

    // -----------------------------------------------------------------------
    // Rule 2: never emit an identity that does not verify
    // -----------------------------------------------------------------------

    /**
     * A stored enrollment whose cert was signed by some OTHER course key must be dropped, not
     * written. `session.start` is signed and hash-chained: a broken claim in there is
     * permanent, unrepairable, and looks exactly like tampering during an adjudication.
     */
    @Test
    fun `a block that fails the chain walk is dropped rather than written`() {
        val s = EnrollmentFixtures.enrolledStore()
        // The manifest anchors to a DIFFERENT course than the stored enrollment names, so
        // step 3 (course_id agreement across all three links) rejects it.
        val otherCourse = EnrollmentFixtures.manifest(courseId = "berkeley-cs61c")
        val skipped = build(s, manifest = otherCourse) as IdentityOutcome.Skipped
        // Keyed by the manifest's course_id, so this reads as "not enrolled in 61C" — the
        // cross-course token never even reaches the chain walk.
        assertTrue(skipped.reason is IdentitySkipReason.NotEnrolled)
    }

    @Test
    fun `an enrollment cert signed by a foreign course key does not verify and is dropped`() {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        // Persist a structurally valid blob whose cert signature is garbage.
        val tampered = EnrollmentFixtures.blobJson()
            .replace(EnrollmentFixtures.cert().courseSig, "ab".repeat(64))
        saveEnrollment(s, tampered)

        val skipped = build(s) as IdentityOutcome.Skipped
        val reason = skipped.reason as IdentitySkipReason.ChainDidNotVerify
        assertTrue(reason.error is IdentityChain.InvalidCourseSignature)
    }

    /**
     * Rule 2, stated at full strength: a **genuinely signed** enrollment cert from a key the
     * `course_cert` does not name must produce NO identity block.
     *
     * This is the case a garbage signature does not cover. Every byte here is a real ed25519
     * signature over a real JCS payload — the cert verifies perfectly against
     * `foreignCoursePriv`'s public half — so nothing about its SHAPE is wrong. The only thing
     * wrong is the signer, and that is exactly what step 1 of the walk exists to catch: it
     * verifies `enrollment_cert` minus `course_sig` against `course_cert.course_pubkey`, not
     * against whatever key happens to have signed it.
     *
     * If this ever emitted, anyone able to mint their own ed25519 keypair could write a
     * self-consistent identity claiming any `student_ref` into a signed, hash-chained
     * `session.start` that can never be amended.
     */
    @Test
    fun `a genuinely signed cert from a key the course cert does not name emits no identity`() {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        // Correct course_id, correct enrollment pubkey, correct student pubkey, real signature
        // over the real payload — signed by a course key the manifest's course_cert never names.
        saveEnrollment(s, EnrollmentFixtures.blobJson(certSigningKey = EnrollmentFixtures.foreignCoursePriv))

        // Sanity: the cert really is validly signed under the foreign key, so this test is
        // about the SIGNER and not about a malformed artifact.
        val foreignCert = EnrollmentFixtures.cert(signingKey = EnrollmentFixtures.foreignCoursePriv)
        assertTrue(
            verifyEnrollmentCert(foreignCert, Ed25519.bytesToHex(Ed25519.publicKeyOf(EnrollmentFixtures.foreignCoursePriv))),
        )
        assertFalse(
            "and it must NOT verify against the course cert's key",
            verifyEnrollmentCert(foreignCert, EnrollmentFixtures.coursePubkeyHex),
        )

        val outcome = build(s)
        assertTrue("a foreign-signed cert must never be emitted", outcome is IdentityOutcome.Skipped)
        val reason = (outcome as IdentityOutcome.Skipped).reason as IdentitySkipReason.ChainDidNotVerify
        assertTrue(reason.error is IdentityChain.InvalidCourseSignature)
    }

    // -----------------------------------------------------------------------
    // The key cache is a timing detail, never a correctness one
    // -----------------------------------------------------------------------

    /**
     * Passing a [CourseKeyCache] must not change a single emitted byte. The cache exists to
     * avoid re-deriving a key; if it ever changed the key, the countersignature would stop
     * verifying against the token's `student_pubkey`.
     */
    @Test
    fun `an identity built through the key cache is identical to one built without it`() {
        val cache = CourseKeyCache()
        val withCache = buildSessionIdentity(
            EnrollmentFixtures.manifest(),
            sessionPubkey,
            startedAt,
            EnrollmentFixtures.enrolledStore(),
            cache,
        ) as IdentityOutcome.Emitted
        val without = build(EnrollmentFixtures.enrolledStore()) as IdentityOutcome.Emitted

        assertEquals(without.identity.toJsonObject(), withCache.identity.toJsonObject())
        assertEquals(EnrollmentFixtures.studentPubkeyHex(), withCache.verified.studentPubkey)
    }

    /**
     * Rule 1 reaches into the cache too: a cache that cannot derive yields no identity and
     * still does not throw, so the session goes on to record.
     */
    @Test
    fun `a key cache that cannot derive skips instead of throwing`() {
        val broken = CourseKeyCache { _, _ -> throw IllegalStateException("no ed25519 provider") }
        val outcome = buildSessionIdentity(
            EnrollmentFixtures.manifest(),
            sessionPubkey,
            startedAt,
            EnrollmentFixtures.enrolledStore(),
            broken,
        )
        assertTrue(outcome is IdentityOutcome.Skipped)
        assertTrue((outcome as IdentityOutcome.Skipped).reason is IdentitySkipReason.MasterSecretUnavailable)
    }

    /**
     * Expiry is REPORTED, never enforced. A course that let its enrollment cert lapse
     * mid-semester must not silently stop identifying an entire class's work.
     */
    @Test
    fun `an out-of-window token is still emitted, with the window reported`() {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        saveEnrollment(s, EnrollmentFixtures.blobJson(expiresAt = "2026-09-02"))

        // Session runs well after the token expired.
        val emitted = build(s, at = "2026-12-01T00:00:00Z") as IdentityOutcome.Emitted
        assertFalse(emitted.verified.tokenWindow.inWindow)
        assertEquals(
            CertWindowReason.AFTER_VALID_UNTIL,
            (emitted.verified.tokenWindow as CertWindowStatus.OutOfWindow).reason,
        )
        // Still a complete, well-formed block.
        assertEquals(
            setOf("enrollment", "enrollment_cert", "session_pubkey_sig"),
            emitted.identity.toJsonObject().keys,
        )
    }

    /**
     * The window is judged against the SESSION START, never wall-clock now — so an archived
     * bundle still reads as in-window years later during an adjudication.
     */
    @Test
    fun `the token window is judged against session start, not now`() {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(EnrollmentFixtures.masterSecret))
        // A window that lapsed long ago in wall-clock terms.
        saveEnrollment(s, EnrollmentFixtures.blobJson(expiresAt = "2026-09-30"))
        val emitted = build(s, at = "2026-09-15T00:00:00Z") as IdentityOutcome.Emitted
        assertTrue(emitted.verified.tokenWindow.inWindow)
    }
}
