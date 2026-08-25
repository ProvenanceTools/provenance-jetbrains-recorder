package dev.provenance.recorder.identity

import dev.provenance.core.CertWindowReason
import dev.provenance.core.CertWindowStatus
import dev.provenance.core.Ed25519
import dev.provenance.core.INSTITUTION_IDENTITY_FORMAT_VERSION
import dev.provenance.core.IdentityChain
import dev.provenance.core.Manifest
import dev.provenance.core.StudentCredential
import dev.provenance.core.StudentSessionBinding
import dev.provenance.core.deriveStudentKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.core.verifyInstitutionCert
import dev.provenance.core.verifyStudentCredential
import dev.provenance.core.verifyStudentSessionBinding
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity-2.1 path of `buildSessionIdentity`: the two rules, the precedence rule, and
 * the emitted shape.
 *
 * Every signature in these fixtures is real, so a chain-walk regression shows up as a
 * SKIPPED identity rather than as a passing test over stubbed crypto.
 */
class InstitutionIdentityBuilderTest {

    private val sessionPubkey = "20".repeat(32)
    private val startedAt = "2026-09-08T12:00:00Z"

    private fun build(
        secrets: SecretStore,
        manifest: Manifest = EnrollmentFixtures.manifest(),
        pubkey: String = sessionPubkey,
        at: String = startedAt,
        rootPubkeyHex: String? = InstitutionFixtures.rootPubkeyHex,
        keyCache: CourseKeyCache? = null,
    ) = buildSessionIdentity(manifest, pubkey, at, secrets, keyCache, rootPubkeyHex)

    // -----------------------------------------------------------------------
    // The happy path and the emitted shape
    // -----------------------------------------------------------------------

    @Test
    fun `a credentialed student emits a chain-verified 2_1 identity`() {
        val emitted = build(InstitutionFixtures.credentialedStore()) as IdentityOutcome.Emitted
        val verified = emitted.verified as IdentityChain.InstitutionOk

        assertEquals(INSTITUTION_IDENTITY_FORMAT_VERSION, verified.identityVersion)
        assertEquals("institution", verified.scope)
        assertEquals(InstitutionFixtures.INSTITUTION_ID, verified.institutionId)
        assertEquals(InstitutionFixtures.STUDENT_REF, verified.studentRef)
        assertEquals(InstitutionFixtures.studentPubkeyHex(), verified.studentPubkey)
        assertEquals(InstitutionFixtures.institutionPubkeyHex, verified.institutionPubkey)
        assertTrue(verified.certWindow.inWindow)
        assertTrue(verified.tokenWindow.inWindow)
    }

    /**
     * The countersignature is over the **v2** binding payload, with the v2 `purpose` tag,
     * and is made by the GLOBAL key — not a per-course one.
     */
    @Test
    fun `the countersignature is the global key over the v2 binding payload`() {
        val emitted = build(InstitutionFixtures.credentialedStore()) as IdentityOutcome.Emitted
        val credential = emitted.identity.enrollment as StudentCredential

        assertTrue(
            verifyStudentSessionBinding(
                StudentSessionBinding(
                    institutionId = InstitutionFixtures.INSTITUTION_ID,
                    studentRef = InstitutionFixtures.STUDENT_REF,
                    sessionPubkey = sessionPubkey,
                ),
                emitted.identity.sessionPubkeySig,
                credential.studentPubkey,
            ),
        )
        // The global derivation, not the per-course one.
        assertEquals(
            deriveStudentKeypair(InstitutionFixtures.masterSecret).publicKeyHex,
            credential.studentPubkey,
        )
        assertNotEquals(EnrollmentFixtures.studentPubkeyHex(), credential.studentPubkey)
    }

    /**
     * EXACTLY three keys, with the two artifacts carrying exactly their contracted 2.1
     * fields. The shape is what an analyzer parses years from now, so it is pinned here
     * rather than left to whatever the data classes happen to serialize.
     */
    @Test
    fun `the emitted 2_1 identity block has exactly the three contracted keys`() {
        val emitted = build(InstitutionFixtures.credentialedStore()) as IdentityOutcome.Emitted
        val block = emitted.identity.toJsonObject()

        assertEquals(setOf("enrollment", "enrollment_cert", "session_pubkey_sig"), block.keys)
        assertEquals(
            setOf(
                "format_version",
                "institution_id",
                "student_ref",
                "student_pubkey",
                "issued_at",
                "expires_at",
                "institution_sig",
            ),
            block["enrollment"]!!.jsonObject.keys,
        )
        assertEquals(
            setOf(
                "format_version",
                "institution_id",
                "institution_pubkey",
                "valid_from",
                "valid_until",
                "root_sig",
            ),
            block["enrollment_cert"]!!.jsonObject.keys,
        )
        assertEquals(
            INSTITUTION_IDENTITY_FORMAT_VERSION,
            block["enrollment_cert"]!!.jsonObject["format_version"]!!.jsonPrimitive.content,
        )
        // The ref is a VALUE at a fixed ASCII key, never promoted to a key itself.
        assertEquals(
            InstitutionFixtures.STUDENT_REF,
            block["enrollment"]!!.jsonObject["student_ref"]!!.jsonPrimitive.content,
        )
    }

    /**
     * A 2.1 credential names no course, so the manifest is not consulted at all. A student
     * with one gets an identity even in a 1.x workspace — which is the point: the 2.0
     * design could not produce an identity before the student's first submission.
     */
    @Test
    fun `a 2_1 identity is emitted even for a 1_x manifest with no course cert`() {
        val emitted = build(
            InstitutionFixtures.credentialedStore(),
            manifest = EnrollmentFixtures.legacyManifest(),
        ) as IdentityOutcome.Emitted
        assertTrue(emitted.verified is IdentityChain.InstitutionOk)
    }

    // -----------------------------------------------------------------------
    // Precedence: 2.1 decides, and never falls back
    // -----------------------------------------------------------------------

    /**
     * A student holding BOTH gets 2.1. The two families attribute to different
     * `student_ref`s — 2.0's is per-course, 2.1's is global — so which one wins is a
     * question about who the session is filed under, not a preference.
     */
    @Test
    fun `a stored 2_1 credential takes precedence over a stored 2_0 token`() {
        val store = EnrollmentFixtures.enrolledStore()
        saveStudentCredential(store, InstitutionFixtures.blobJson())

        val emitted = build(store) as IdentityOutcome.Emitted
        val verified = emitted.verified as IdentityChain.InstitutionOk
        assertEquals(InstitutionFixtures.STUDENT_REF, verified.studentRef)
        assertNotEquals(EnrollmentFixtures.STUDENT_REF, verified.studentRef)
    }

    /**
     * And when the 2.1 path FAILS there is deliberately no fallback to 2.0, even though a
     * perfectly good 2.0 token is sitting right there. Falling back would file the session
     * under a different contributor than the student believes, and would hide the 2.1
     * problem that caused it. Recording is unaffected either way — only the identity block
     * is withheld.
     */
    @Test
    fun `a failing 2_1 path does NOT silently fall back to a usable 2_0 token`() {
        val store = EnrollmentFixtures.enrolledStore()
        // A credential signed by an institution key the root never certified.
        saveStudentCredential(
            store,
            InstitutionFixtures.blobJson(
                credential = InstitutionFixtures.credential(
                    signingKey = InstitutionFixtures.foreignInstitutionPriv,
                ),
            ),
        )

        val skipped = build(store) as IdentityOutcome.Skipped
        val reason = skipped.reason as IdentitySkipReason.ChainDidNotVerify
        assertEquals("invalid_institution_signature", reason.error.kind)
    }

    // -----------------------------------------------------------------------
    // Rule 2: never emit an identity that does not verify
    // -----------------------------------------------------------------------

    /**
     * THE mandatory negative, at the recorder. An institution genuinely certified by root
     * for `stanford` issues a credential naming `berkeley` and ships its own genuine cert.
     * Every signature verifies; only comparing `institution_id` across all three links
     * refuses it — and the recorder must refuse to WRITE it, because `session.start` is
     * signed and hash-chained and a bad claim in there is permanent and unrepairable.
     *
     * **The blob is planted directly in the store, bypassing [saveStudentCredential].**
     * That is deliberate and it is the only way to reach the walk with this input: import
     * already rejects a credential and cert that disagree about the institution. The
     * planted path is the realistic one anyway — the credential vault holds the student's
     * own entries, and Rule 2 exists precisely so that a store which has been hand-edited,
     * corrupted, or written by an older build still cannot get an unverifiable claim into
     * a signed log.
     *
     * Note what this test also documents: on THIS recorder the anchor is always the stored
     * cert, root-verified. So `institution_mismatch` is reachable here only when the
     * credential and its travelling cert disagree. The analyzer, which gets its anchor
     * from configuration rather than from the bundle, is where the third comparison
     * (against an independently-known anchor) does the heavy lifting — and `identity.json`
     * pins that case for both.
     */
    @Test
    fun `a cross-institution forgery is never written into session_start`() {
        val stanfordCert = InstitutionFixtures.cert(
            institutionId = InstitutionFixtures.OTHER_INSTITUTION_ID,
            institutionPubkey = InstitutionFixtures.foreignInstitutionPubkeyHex,
        )
        val berkeleyClaiming = InstitutionFixtures.credential(
            institutionId = InstitutionFixtures.INSTITUTION_ID,
            signingKey = InstitutionFixtures.foreignInstitutionPriv,
        )

        // Every signature really is genuine.
        assertTrue(verifyInstitutionCert(stanfordCert, InstitutionFixtures.rootPubkeyHex))
        assertTrue(verifyStudentCredential(berkeleyClaiming, stanfordCert.institutionPubkey))

        // Import refuses it outright — the first line of defence.
        val viaImport = saveStudentCredential(
            FakeSecretStore(),
            InstitutionFixtures.blobJson(berkeleyClaiming, stanfordCert),
        ) as StoreResult.Err
        assertTrue(viaImport.error is IdentityStoreError.InstitutionIdMismatch)

        // Planted past it, the chain walk is the second, and it is the one that matters:
        // it stands between the forgery and a permanent signed claim.
        val store = FakeSecretStore()
        importMasterSecret(store, Ed25519.bytesToHex(InstitutionFixtures.masterSecret))
        store.store(CREDENTIAL_KEY, InstitutionFixtures.blobJson(berkeleyClaiming, stanfordCert))
        assertNotNull(loadStudentCredential(store))

        val skipped = build(store) as IdentityOutcome.Skipped
        val reason = skipped.reason as IdentitySkipReason.ChainDidNotVerify
        val err = reason.error as IdentityChain.InstitutionMismatch
        assertEquals("institution_mismatch", err.kind)
        assertEquals(InstitutionFixtures.INSTITUTION_ID, err.credentialInstitutionId)
        assertEquals(InstitutionFixtures.OTHER_INSTITUTION_ID, err.certInstitutionId)
        // Keys agree; only the ids do not. A verifier comparing only keys would accept it.
        assertFalse(err.pubkeyMismatch)
    }

    /**
     * The anchor must be ROOT-verified before it is used as an anchor. An attacker who
     * supplies the cert supplies its `institution_pubkey` too, so a cert that is not
     * root-signed proves nothing about anything beneath it.
     */
    @Test
    fun `a cert that is not root-signed is refused before the chain walk`() {
        val skipped = build(
            InstitutionFixtures.credentialedStore(
                cert = InstitutionFixtures.cert(signingKey = InstitutionFixtures.wrongRootPriv),
            ),
        ) as IdentityOutcome.Skipped
        assertEquals(IdentitySkipReason.InstitutionCertNotRootSigned, skipped.reason)
    }

    /** No embedded root key means no anchor. A build problem, reported as one. */
    @Test
    fun `an absent or malformed root public key is reported, never guessed at`() {
        assertEquals(
            IdentitySkipReason.NoRootPublicKey,
            (
                build(InstitutionFixtures.credentialedStore(), rootPubkeyHex = null)
                    as IdentityOutcome.Skipped
                ).reason,
        )
        assertEquals(
            IdentitySkipReason.NoRootPublicKey,
            (
                build(InstitutionFixtures.credentialedStore(), rootPubkeyHex = "not-hex")
                    as IdentityOutcome.Skipped
                ).reason,
        )
    }

    /**
     * A credential naming a key this machine's master secret does not derive — normally a
     * credential minted before the student imported a different secret. Signing anyway
     * would produce a countersignature that cannot verify, which is indistinguishable from
     * tampering during an adjudication.
     */
    @Test
    fun `a credential naming a key this machine cannot derive is skipped, not signed`() {
        val other = deriveStudentKeypair(ByteArray(32) { 0x7f }).publicKeyHex
        val skipped = build(
            InstitutionFixtures.credentialedStore(
                credential = InstitutionFixtures.credential(studentPubkey = other),
            ),
        ) as IdentityOutcome.Skipped
        val reason = skipped.reason as IdentitySkipReason.CredentialKeyMismatch
        assertEquals(other, reason.credentialStudentPubkey)
        assertEquals(InstitutionFixtures.studentPubkeyHex(), reason.derivedPubkey)
    }

    /** A master secret that is absent is "not enrolled", never a reason to stop recording. */
    @Test
    fun `an absent master secret skips the identity rather than failing`() {
        val store = FakeSecretStore()
        saveStudentCredential(store, InstitutionFixtures.blobJson())
        val skipped = build(store) as IdentityOutcome.Skipped
        assertTrue(skipped.reason is IdentitySkipReason.MasterSecretUnavailable)
    }

    /** A session key this recorder could not have generated is reported distinctly. */
    @Test
    fun `a non-hex session pubkey is reported distinctly`() {
        val skipped = build(
            InstitutionFixtures.credentialedStore(),
            pubkey = "not-hex",
        ) as IdentityOutcome.Skipped
        assertEquals(IdentitySkipReason.InvalidSessionPubkey, skipped.reason)
    }

    // -----------------------------------------------------------------------
    // Expiry is REPORTED, never enforced
    // -----------------------------------------------------------------------

    /**
     * An out-of-window credential still produces an identity, with the window reported on
     * the success value. For an integrity tool, silently not recording for a whole
     * institution is a worse failure than recording under a stale credential.
     */
    @Test
    fun `an expired credential is emitted with the window reported, not withheld`() {
        val emitted = build(
            InstitutionFixtures.credentialedStore(
                credential = InstitutionFixtures.credential(
                    issuedAt = "2025-09-01T00:00:00Z",
                    expiresAt = "2025-12-15",
                ),
            ),
        ) as IdentityOutcome.Emitted
        val verified = emitted.verified as IdentityChain.InstitutionOk
        assertEquals(
            CertWindowReason.AFTER_VALID_UNTIL,
            (verified.tokenWindow as CertWindowStatus.OutOfWindow).reason,
        )
    }

    /** The cert side of the same rule. */
    @Test
    fun `an expired institution cert is emitted with the window reported`() {
        val expired = InstitutionFixtures.cert(validFrom = "2020-01-01", validUntil = "2020-12-31")
        val emitted = build(
            InstitutionFixtures.credentialedStore(cert = expired),
        ) as IdentityOutcome.Emitted
        val verified = emitted.verified as IdentityChain.InstitutionOk
        assertEquals(
            CertWindowReason.AFTER_VALID_UNTIL,
            (verified.certWindow as CertWindowStatus.OutOfWindow).reason,
        )
        // ...and the credential itself is still in date.
        assertTrue(verified.tokenWindow.inWindow)
    }

    // -----------------------------------------------------------------------
    // The cache is a performance detail, never a correctness one
    // -----------------------------------------------------------------------

    /**
     * A cache hit, a cache miss and no cache at all must produce byte-identical
     * countersignatures. Anything else means the key shown to the student and the key that
     * signs are not the same key.
     */
    @Test
    fun `the global key cache and direct derivation produce the same signature`() {
        val store = InstitutionFixtures.credentialedStore()
        val cache = CourseKeyCache()
        try {
            val withCache = build(store, keyCache = cache) as IdentityOutcome.Emitted
            val again = build(store, keyCache = cache) as IdentityOutcome.Emitted
            val direct = build(store) as IdentityOutcome.Emitted

            assertEquals(direct.identity.sessionPubkeySig, withCache.identity.sessionPubkeySig)
            assertEquals(direct.identity.sessionPubkeySig, again.identity.sessionPubkeySig)
            assertEquals(1, cache.size)
        } finally {
            cache.dispose()
        }
    }

    /**
     * The global cache is keyed on a fingerprint of the master secret, so importing a
     * different secret mid-session cannot keep returning keys derived from the old one.
     */
    @Test
    fun `the global cache is keyed on the master secret, not on nothing`() {
        val cache = CourseKeyCache()
        try {
            val a = cache.getGlobal(ByteArray(32) { 0x2a })
            val b = cache.getGlobal(ByteArray(32) { 0x2b })
            assertNotEquals(a!!.publicKeyHex, b!!.publicKeyHex)
            assertEquals(2, cache.size)
        } finally {
            cache.dispose()
        }
    }

    /** Disposal drops both derivations; the cache still answers, it just retains nothing. */
    @Test
    fun `disposal drops the global keys too`() {
        val cache = CourseKeyCache()
        cache.getGlobal(InstitutionFixtures.masterSecret)
        cache.get(InstitutionFixtures.masterSecret, EnrollmentFixtures.COURSE_ID)
        assertEquals(2, cache.size)
        cache.dispose()
        assertEquals(0, cache.size)
        // Still correct after disposal — a caching layer must never be the reason a
        // student's work goes unrecorded.
        assertEquals(
            InstitutionFixtures.studentPubkeyHex(),
            cache.getGlobal(InstitutionFixtures.masterSecret)!!.publicKeyHex,
        )
        assertEquals(0, cache.size)
    }

    // -----------------------------------------------------------------------
    // Storage round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `a stored credential round-trips through the secret store`() {
        val store = InstitutionFixtures.credentialedStore()
        val loaded = loadStudentCredential(store)!!
        assertEquals(InstitutionFixtures.credential(), loaded.enrollment)
        assertEquals(InstitutionFixtures.cert(), loaded.enrollmentCert)

        clearStudentCredential(store)
        assertNull(loadStudentCredential(store))
        // Clearing the credential NEVER touches the master secret.
        assertEquals(
            Ed25519.bytesToHex(InstitutionFixtures.masterSecret),
            (exportMasterSecret(store) as StoreResult.Ok).value,
        )
    }
}
