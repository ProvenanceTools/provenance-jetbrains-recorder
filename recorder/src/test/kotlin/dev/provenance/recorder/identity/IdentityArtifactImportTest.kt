package dev.provenance.recorder.identity

import dev.provenance.core.Ed25519
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [saveIdentityArtifact] — the ONE importer, and the rule that decides which family a
 * pasted blob belongs to.
 *
 * Both versions use the SAME two wire slots, so "which keys are present" says nothing at
 * all about which version a blob is. The discriminator is the `format_version` INSIDE
 * `enrollment_cert`, which is signed in both families. This project already shipped the
 * other design once — a reader treated the mere PRESENCE of a field as a version claim and
 * made a whole legacy path unreachable — so the presence-based tests below are the ones
 * that matter most.
 */
class IdentityArtifactImportTest {

    private fun store() = FakeSecretStore()

    // -----------------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------------

    @Test
    fun `a 2_1 blob is routed to the institution family and stored`() {
        val s = store()
        val ok = saveIdentityArtifact(s, InstitutionFixtures.blobJson()) as StoreResult.Ok
        val v = ok.value as IdentityImportOk.Current21

        assertEquals(InstitutionFixtures.INSTITUTION_ID, v.institutionId)
        assertEquals(InstitutionFixtures.STUDENT_REF, v.studentRef)
        assertEquals(InstitutionFixtures.studentPubkeyHex(), v.studentPubkey)
        assertNotNull(loadStudentCredential(s))
        // ...and nothing was written to the 2.0 keyspace.
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))
    }

    @Test
    fun `a 2_0 blob is routed to the legacy family and still stored, forever`() {
        val s = store()
        val ok = saveIdentityArtifact(s, EnrollmentFixtures.blobJson()) as StoreResult.Ok
        val v = ok.value as IdentityImportOk.Legacy20

        assertEquals(EnrollmentFixtures.COURSE_ID, v.courseId)
        assertNotNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))
        assertNull(loadStudentCredential(s))
    }

    /**
     * THE routing test. A 2.1 credential paired with a cert relabelled `"2.0"` must NOT be
     * read as legacy just because the fields look institution-shaped, and must not be read
     * as 2.1 either. It is routed by the declared version — to the 2.0 family — and
     * refused there on shape, because an institution cert carries no `course_id`.
     *
     * A presence-based router would have stored this as a 2.1 credential.
     */
    @Test
    fun `routing follows the declared version even when the fields say otherwise`() {
        val s = store()
        val blob = buildJsonObject {
            put("enrollment", InstitutionFixtures.credential().toJsonObject())
            put(
                "enrollment_cert",
                InstitutionFixtures.cert(formatVersion = "2.0").toJsonObject(),
            )
        }.toString()

        val err = saveIdentityArtifact(s, blob) as StoreResult.Err
        // Routed to 2.0, which refuses it at its own version gate: the credential slot
        // still declares 2.1.
        val e = err.error as IdentityStoreError.UnsupportedFormatVersion
        assertEquals("token", e.artifact)
        assertEquals("2.1", e.formatVersion)
        assertNull(loadStudentCredential(s))
        assertNull(loadEnrollment(s, InstitutionFixtures.INSTITUTION_ID))
    }

    /** A version neither family implements is refused by the router itself. */
    @Test
    fun `an unknown identity version is refused by the router`() {
        val s = store()
        val blob = buildJsonObject {
            put("enrollment", InstitutionFixtures.credential(formatVersion = "3.0").toJsonObject())
            put("enrollment_cert", InstitutionFixtures.cert(formatVersion = "3.0").toJsonObject())
        }.toString()

        val err = saveIdentityArtifact(s, blob) as StoreResult.Err
        assertEquals(
            "3.0",
            (err.error as IdentityStoreError.UnsupportedIdentityVersion).formatVersion,
        )
        assertNull(loadStudentCredential(s))
    }

    @Test
    fun `a blob with no cert slot at all is refused, not guessed at`() {
        val s = store()
        val err = saveIdentityArtifact(s, """{"enrollment":{}}""") as StoreResult.Err
        assertEquals("", (err.error as IdentityStoreError.UnsupportedIdentityVersion).formatVersion)
    }

    @Test
    fun `malformed json is a value, not a throw`() {
        val err = saveIdentityArtifact(store(), "not json") as StoreResult.Err
        assertTrue(err.error is IdentityStoreError.InvalidJson)
        val notObject = saveIdentityArtifact(store(), "[1,2,3]") as StoreResult.Err
        assertTrue(notObject.error is IdentityStoreError.InvalidJson)
    }

    // -----------------------------------------------------------------------
    // The 2.1 importer's own checks
    // -----------------------------------------------------------------------

    /**
     * The version gate runs BEFORE shape, mirroring `verifyIdentityChain` step 0: a future
     * artifact must be refused as a version problem, never read under 2.1 rules and then
     * reported as malformed.
     */
    @Test
    fun `the 2_1 version gate runs before shape validation`() {
        val s = store()
        // A cert that is BOTH the wrong version AND structurally broken. The version is
        // what must be reported.
        val blob = buildJsonObject {
            put("enrollment", InstitutionFixtures.credential().toJsonObject())
            put(
                "enrollment_cert",
                buildJsonObject { put("format_version", "3.0") },
            )
        }.toString()

        val err = saveStudentCredential(s, blob) as StoreResult.Err
        val e = err.error as IdentityStoreError.UnsupportedFormatVersion
        assertEquals("cert", e.artifact)
        assertEquals("3.0", e.formatVersion)
    }

    /**
     * A credential and a cert naming different institutions is two pastes mixed together.
     * Caught here as well as in the chain walk: storing a pair that can never verify would
     * leave the student believing they are enrolled while every session silently omitted
     * an identity.
     */
    @Test
    fun `a credential and cert naming different institutions are refused at import`() {
        val s = store()
        val blob = InstitutionFixtures.blobJson(
            credential = InstitutionFixtures.credential(institutionId = "berkeley"),
            cert = InstitutionFixtures.cert(institutionId = "stanford"),
        )
        val err = saveStudentCredential(s, blob) as StoreResult.Err
        val e = err.error as IdentityStoreError.InstitutionIdMismatch
        assertEquals("berkeley", e.credentialInstitutionId)
        assertEquals("stanford", e.certInstitutionId)
        assertNull(loadStudentCredential(s))
    }

    @Test
    fun `a malformed 2_1 artifact is refused with the slot named`() {
        val s = store()
        val badCert = buildJsonObject {
            put("enrollment", InstitutionFixtures.credential().toJsonObject())
            put(
                "enrollment_cert",
                buildJsonObject {
                    put("format_version", "2.1")
                    put("institution_id", "berkeley")
                    // institution_pubkey / valid_* / root_sig all missing
                },
            )
        }.toString()
        assertTrue(
            (saveStudentCredential(s, badCert) as StoreResult.Err).error
                is IdentityStoreError.InvalidCertShape,
        )

        val badCredential = buildJsonObject {
            put(
                "enrollment",
                buildJsonObject {
                    put("format_version", "2.1")
                    put("institution_id", "berkeley")
                },
            )
            put("enrollment_cert", InstitutionFixtures.cert().toJsonObject())
        }.toString()
        assertTrue(
            (saveStudentCredential(s, badCredential) as StoreResult.Err).error
                is IdentityStoreError.InvalidCredentialShape,
        )
    }

    /**
     * SIGNATURES ARE NOT CHECKED AT IMPORT, at either version — the 2.1 trust anchor is the
     * recorder's embedded root key and the real walk happens at session start. This is
     * pinned so nobody "fixes" it by adding a check here and then relaxes the one that
     * matters.
     *
     * Note what this means: import cannot detect the cross-institution forgery, because
     * that needs the root-verified anchor. `buildSessionIdentity` does, and refuses to
     * write it — see `InstitutionIdentityBuilderTest`.
     */
    @Test
    fun `import stores an unverifiable pair, and session start is what refuses it`() {
        val s = store()
        importMasterSecret(s, Ed25519.bytesToHex(InstitutionFixtures.masterSecret))
        val stanfordCert = InstitutionFixtures.cert(
            institutionId = "stanford",
            institutionPubkey = InstitutionFixtures.foreignInstitutionPubkeyHex,
        )
        // A cert not signed by root at all — import does not care.
        val unanchored = InstitutionFixtures.blobJson(
            credential = InstitutionFixtures.credential(
                institutionId = "stanford",
                signingKey = InstitutionFixtures.foreignInstitutionPriv,
            ),
            cert = stanfordCert.copy(rootSig = "ab".repeat(64)),
        )
        assertTrue(saveIdentityArtifact(s, unanchored) is StoreResult.Ok)
        assertNotNull(loadStudentCredential(s))

        val skipped = buildSessionIdentity(
            EnrollmentFixtures.manifest(),
            "20".repeat(32),
            "2026-09-08T12:00:00Z",
            s,
            null,
            InstitutionFixtures.rootPubkeyHex,
        ) as IdentityOutcome.Skipped
        assertEquals(IdentitySkipReason.InstitutionCertNotRootSigned, skipped.reason)
    }
}
