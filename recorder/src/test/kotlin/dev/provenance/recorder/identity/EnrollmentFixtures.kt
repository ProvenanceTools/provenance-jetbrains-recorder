package dev.provenance.recorder.identity

import dev.provenance.core.CourseCert
import dev.provenance.core.ENROLLMENT_FORMAT_VERSION
import dev.provenance.core.Ed25519
import dev.provenance.core.EnrollmentCert
import dev.provenance.core.EnrollmentToken
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestCollaboration
import dev.provenance.core.ManifestScope
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.core.signCourseCert
import dev.provenance.core.signEnrollmentCert
import dev.provenance.core.signEnrollmentToken
import dev.provenance.core.signManifest
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A complete, genuinely-signed identity chain for the recorder-side tests: root → course →
 * enrollment key → token → student key.
 *
 * Everything is real ed25519 over real JCS payloads, from fixed seeds. Nothing here is a
 * stub: `buildSessionIdentity` runs the same `verifyIdentityChain` the analyzer runs, so a
 * faked signature would simply be skipped and the tests would prove nothing.
 */
object EnrollmentFixtures {
    const val COURSE_ID: String = "berkeley-cs61b"
    const val STUDENT_REF: String = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    val rootPriv: ByteArray = ByteArray(32) { 0x51 }
    val coursePriv: ByteArray = ByteArray(32) { 0x52 }
    val enrollmentPriv: ByteArray = ByteArray(32) { 0x53 }

    /**
     * A real ed25519 course key that NO course_cert in these fixtures names. Used to mint a
     * GENUINELY SIGNED enrollment cert that the chain must still reject at step 1: the point of
     * the identity chain is that a valid signature from the wrong signer is worth nothing.
     */
    val foreignCoursePriv: ByteArray = ByteArray(32) { 0x66 }

    /** The student's 32-byte master secret. Per-course keys derive from this. */
    val masterSecret: ByteArray = ByteArray(32) { 0x2a }

    val rootPubkeyHex: String get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(rootPriv))
    val coursePubkeyHex: String get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(coursePriv))
    val enrollmentPubkeyHex: String get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(enrollmentPriv))

    fun studentPubkeyHex(courseId: String = COURSE_ID): String =
        deriveCourseKeypair(masterSecret, courseId).publicKeyHex

    /** The root-signed course certificate that anchors the whole chain. */
    fun courseCert(courseId: String = COURSE_ID): CourseCert {
        val unsigned = CourseCert(
            courseId = courseId,
            coursePubkey = coursePubkeyHex,
            validFrom = "2026-08-20",
            validUntil = "2027-01-15",
            rootSig = "",
        )
        return unsigned.copy(rootSig = signCourseCert(unsigned, rootPriv))
    }

    /** The course-signed authorization for the server-held enrollment key. */
    fun cert(
        courseId: String = COURSE_ID,
        formatVersion: String = ENROLLMENT_FORMAT_VERSION,
        validFrom: String = "2026-08-20",
        validUntil: String = "2027-01-15",
        /** Which course key signs it. Defaults to the one [courseCert] names. */
        signingKey: ByteArray = coursePriv,
    ): EnrollmentCert {
        val unsigned = EnrollmentCert(
            formatVersion = formatVersion,
            courseId = courseId,
            enrollmentPubkey = enrollmentPubkeyHex,
            validFrom = validFrom,
            validUntil = validUntil,
            courseSig = "",
        )
        return unsigned.copy(courseSig = signEnrollmentCert(unsigned, signingKey))
    }

    /** The enrollment-signed statement binding a student pubkey to a roster entry. */
    fun token(
        courseId: String = COURSE_ID,
        formatVersion: String = ENROLLMENT_FORMAT_VERSION,
        studentPubkey: String = studentPubkeyHex(courseId),
        issuedAt: String = "2026-09-01T00:00:00Z",
        expiresAt: String = "2027-01-15",
    ): EnrollmentToken {
        val unsigned = EnrollmentToken(
            formatVersion = formatVersion,
            studentRef = STUDENT_REF,
            courseId = courseId,
            studentPubkey = studentPubkey,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            enrollmentSig = "",
        )
        return unsigned.copy(enrollmentSig = signEnrollmentToken(unsigned, enrollmentPriv))
    }

    /** The `{ enrollment, enrollment_cert }` blob a student pastes into the import command. */
    fun blobJson(
        courseId: String = COURSE_ID,
        certCourseId: String = courseId,
        certFormatVersion: String = ENROLLMENT_FORMAT_VERSION,
        tokenFormatVersion: String = ENROLLMENT_FORMAT_VERSION,
        studentPubkey: String = studentPubkeyHex(courseId),
        expiresAt: String = "2027-01-15",
        /** Which course key signs the enrollment cert. Defaults to the legitimate one. */
        certSigningKey: ByteArray = coursePriv,
    ): String = buildJsonObject {
        put(
            "enrollment",
            token(
                courseId = courseId,
                formatVersion = tokenFormatVersion,
                studentPubkey = studentPubkey,
                expiresAt = expiresAt,
            ).toJsonObject(),
        )
        put(
            "enrollment_cert",
            cert(
                courseId = certCourseId,
                formatVersion = certFormatVersion,
                signingKey = certSigningKey,
            ).toJsonObject(),
        )
    }.toString()

    /** A signed Manifest 2.0 whose `course_cert` anchors the identity chain. */
    fun manifest(courseId: String = COURSE_ID): Manifest {
        val unsigned = Manifest(
            assignmentId = "proj2",
            semester = "fa26",
            issuedAt = "2026-09-08T00:00:00Z",
            filesUnderReview = listOf("src/Main.java"),
            sig = "",
            formatVersion = "2.0",
            courseId = courseId,
            collaboration = ManifestCollaboration.SOLO,
            submission = ManifestSubmission.BUNDLE,
            scope = ManifestScope.DIRECTORY,
            policy = Json.parseToJsonElement("""{"capture":{"terminal":true}}""").jsonObject,
            courseCert = courseCert(courseId),
        )
        return unsigned.copy(sig = signManifest(unsigned, coursePriv))
    }

    /** A 1.x manifest: no course_id, no course_cert, so no identity chain to anchor. */
    fun legacyManifest(): Manifest =
        Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    /** A store already holding this student's master secret and a valid enrollment. */
    fun enrolledStore(courseId: String = COURSE_ID): FakeSecretStore {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(masterSecret))
        saveEnrollment(s, blobJson(courseId = courseId))
        return s
    }
}
