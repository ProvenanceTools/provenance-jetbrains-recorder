package dev.provenance.recorder.identity

import dev.provenance.core.Ed25519
import dev.provenance.core.deriveCourseKeypair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory [SecretStore] — the seam that lets these run without an IDE. */
class FakeSecretStore(
    private val map: MutableMap<String, String> = mutableMapOf(),
    /** When set, every operation throws it — a locked keychain / headless Linux box. */
    var failure: Throwable? = null,
) : SecretStore {
    override fun get(key: String): String? {
        failure?.let { throw it }
        return map[key]
    }

    override fun store(key: String, value: String) {
        failure?.let { throw it }
        map[key] = value
    }

    override fun delete(key: String) {
        failure?.let { throw it }
        map.remove(key)
    }

    fun raw(key: String): String? = map[key]

    fun put(key: String, value: String) {
        map[key] = value
    }
}

class StudentIdentityStoreTest {

    private fun store() = FakeSecretStore()

    // -----------------------------------------------------------------------
    // Master secret
    // -----------------------------------------------------------------------

    @Test
    fun `loadOrCreate generates once and is stable thereafter`() {
        val s = store()
        val first = loadOrCreateMasterSecret(s) as StoreResult.Ok
        assertEquals(32, first.value.size)
        val second = loadOrCreateMasterSecret(s) as StoreResult.Ok
        assertArrayEquals(first.value, second.value)
        // Persisted as 64 lowercase hex under the fixed key.
        assertTrue(s.raw(MASTER_SECRET_KEY)!!.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `load does not create`() {
        val s = store()
        val r = loadMasterSecret(s) as StoreResult.Err
        assertTrue(r.error is IdentityStoreError.NoMasterSecret)
        assertNull(s.raw(MASTER_SECRET_KEY))
    }

    /**
     * A corrupt stored value must NEVER be silently regenerated. Regenerating changes every
     * derived per-course key, which invalidates every enrollment token the student holds —
     * an unrecoverable loss dressed up as self-healing.
     */
    @Test
    fun `a corrupt master secret is an error and is never overwritten`() {
        val s = store()
        s.put(MASTER_SECRET_KEY, "not-hex-at-all")

        val loaded = loadMasterSecret(s) as StoreResult.Err
        assertTrue(loaded.error is IdentityStoreError.CorruptMasterSecret)

        val created = loadOrCreateMasterSecret(s) as StoreResult.Err
        assertTrue(created.error is IdentityStoreError.CorruptMasterSecret)

        assertEquals("not-hex-at-all", s.raw(MASTER_SECRET_KEY))
    }

    @Test
    fun `an unavailable keyring is reported, not mistaken for absence`() {
        val s = store()
        s.failure = IllegalStateException("keyring locked")
        val r = loadMasterSecret(s) as StoreResult.Err
        assertTrue(r.error is IdentityStoreError.SecretStoreUnavailable)
    }

    /**
     * The new-machine path. Export on the old machine, import on the new, and per-course
     * keys re-derive BYTE-IDENTICALLY — which is what makes existing enrollment tokens keep
     * working instead of having to be re-minted.
     */
    @Test
    fun `export then import on another machine re-derives identical per-course keys`() {
        val oldMachine = store()
        val master = (loadOrCreateMasterSecret(oldMachine) as StoreResult.Ok).value
        val exported = (exportMasterSecret(oldMachine) as StoreResult.Ok).value
        assertEquals(Ed25519.bytesToHex(master), exported)

        val newMachine = store()
        assertTrue(importMasterSecret(newMachine, exported) is StoreResult.Ok)

        val reloaded = (loadMasterSecret(newMachine) as StoreResult.Ok).value
        assertArrayEquals(master, reloaded)
        for (courseId in listOf("berkeley-cs61b", "berkeley-cs61c")) {
            assertEquals(
                deriveCourseKeypair(master, courseId).publicKeyHex,
                deriveCourseKeypair(reloaded, courseId).publicKeyHex,
            )
        }
    }

    @Test
    fun `import tolerates whitespace and case from a pasted secret`() {
        val s = store()
        val hex = "ab".repeat(32)
        assertTrue(importMasterSecret(s, "  ${hex.uppercase()}\n") is StoreResult.Ok)
        assertEquals(hex, s.raw(MASTER_SECRET_KEY))
    }

    /** A typo must not destroy an identity that cannot be recovered from anywhere else. */
    @Test
    fun `a malformed import leaves the existing secret untouched`() {
        val s = store()
        val original = (loadOrCreateMasterSecret(s) as StoreResult.Ok).value
        val r = importMasterSecret(s, "oops") as StoreResult.Err
        assertTrue(r.error is IdentityStoreError.CorruptMasterSecret)
        assertArrayEquals(original, (loadMasterSecret(s) as StoreResult.Ok).value)
    }

    // -----------------------------------------------------------------------
    // Enrollment tokens
    // -----------------------------------------------------------------------

    @Test
    fun `a valid enrollment blob round-trips under its own course id`() {
        val s = store()
        val saved = saveEnrollment(s, EnrollmentFixtures.blobJson()) as StoreResult.Ok
        assertEquals(EnrollmentFixtures.COURSE_ID, saved.value)

        val loaded = loadEnrollment(s, EnrollmentFixtures.COURSE_ID)
        assertNotNull(loaded)
        assertEquals(EnrollmentFixtures.token(), loaded!!.enrollment)
        assertEquals(EnrollmentFixtures.cert(), loaded.enrollmentCert)

        // Keyed per course: another course reads as not enrolled, never as a half-state.
        assertNull(loadEnrollment(s, "berkeley-cs61c"))
    }

    @Test
    fun `version is gated before shape, so a future artifact is a version error`() {
        val s = store()
        val future = EnrollmentFixtures.blobJson(certFormatVersion = "3.0")
        val r = saveEnrollment(s, future) as StoreResult.Err
        val err = r.error as IdentityStoreError.UnsupportedFormatVersion
        assertEquals("cert", err.artifact)
        assertEquals("3.0", err.formatVersion)
    }

    /**
     * Rejected at import, while the student is standing there to fix it, as well as by the
     * chain walk later. Storing a pair that can never verify would leave them believing they
     * are enrolled while every session silently omitted an identity.
     */
    @Test
    fun `a token and cert naming different courses is rejected`() {
        val s = store()
        val r = saveEnrollment(s, EnrollmentFixtures.blobJson(certCourseId = "berkeley-cs61c"))
        val err = (r as StoreResult.Err).error as IdentityStoreError.CourseIdMismatch
        assertEquals(EnrollmentFixtures.COURSE_ID, err.tokenCourseId)
        assertEquals("berkeley-cs61c", err.certCourseId)
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))
    }

    @Test
    fun `malformed json is an error, not a throw`() {
        val r = saveEnrollment(store(), "{not json") as StoreResult.Err
        assertTrue(r.error is IdentityStoreError.InvalidJson)
    }

    /**
     * Every read failure collapses to null. This runs on the session-start path, where the
     * only correct response to "cannot produce an identity" is to record without one.
     */
    @Test
    fun `every enrollment read failure reads as not enrolled`() {
        val s = store()
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))

        s.put(enrollmentKeyForCourse(EnrollmentFixtures.COURSE_ID), "{corrupt")
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))

        s.put(enrollmentKeyForCourse(EnrollmentFixtures.COURSE_ID), """{"enrollment":{}}""")
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))

        s.failure = IllegalStateException("keyring gone")
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))
    }

    @Test
    fun `clearing an enrollment never touches the master secret`() {
        val s = store()
        loadOrCreateMasterSecret(s)
        saveEnrollment(s, EnrollmentFixtures.blobJson())
        clearEnrollment(s, EnrollmentFixtures.COURSE_ID)
        assertNull(loadEnrollment(s, EnrollmentFixtures.COURSE_ID))
        assertTrue(loadMasterSecret(s) is StoreResult.Ok)
    }
}
