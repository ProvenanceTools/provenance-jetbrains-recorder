package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `policy.enrollment` rides inside the same `policy` [kotlinx.serialization.json.JsonObject]
 * `capture` already lives in — [buildSignedPayload]'s `m.policy?.let { put("policy", it) }`
 * treats the whole block as opaque and passes it through verbatim. These tests prove that
 * byte-for-byte: a manifest carrying `policy.enrollment` signs and chain-verifies exactly
 * like one that doesn't, and adding it changes nothing about the signed payload's SHAPE —
 * `enrollment` is just more content inside a key the signer never inspects.
 */
class EnrollmentPolicyManifestTest {
    private val rootPriv = ByteArray(32) { 0x11 }
    private val coursePriv = ByteArray(32) { 0x22 }
    private val rootPubkeyHex get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(rootPriv))
    private val coursePubkeyHex get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(coursePriv))

    private fun cert(courseId: String): CourseCert {
        val unsigned = CourseCert(
            courseId = courseId,
            coursePubkey = coursePubkeyHex,
            validFrom = "2026-01-01",
            validUntil = "2027-01-01",
            rootSig = "",
        )
        return unsigned.copy(rootSig = signCourseCert(unsigned, rootPriv))
    }

    private fun manifestWithPolicy(policyJson: String, courseId: String = "berkeley-cs61a"): Manifest {
        val unsigned = Manifest(
            assignmentId = "hw1",
            semester = "fa26",
            issuedAt = "2026-08-25T00:00:00Z",
            filesUnderReview = listOf("hw1.py"),
            sig = "",
            formatVersion = MANIFEST_FORMAT_VERSION_2,
            courseId = courseId,
            ignore = emptyList(),
            attachments = emptyList(),
            collaboration = ManifestCollaboration.SOLO,
            submission = ManifestSubmission.BUNDLE,
            scope = ManifestScope.DIRECTORY,
            policy = Json.parseToJsonElement(policyJson).jsonObject,
            courseCert = cert(courseId),
        )
        return unsigned.copy(sig = signManifest(unsigned, coursePriv))
    }

    @Test
    fun `a manifest carrying policy enrollment signs and verifies`() {
        val m = manifestWithPolicy("""{"enrollment":{"required":false}}""")
        assertTrue(verifyManifest(m, coursePubkeyHex))
    }

    @Test
    fun `a manifest carrying policy enrollment chain-verifies against the root key`() {
        val m = manifestWithPolicy("""{"enrollment":{"required":false}}""")
        assertInstanceOf(ManifestChain.Ok::class.java, verifyManifestChain(m, rootPubkeyHex))
    }

    @Test
    fun `tampering with the enrollment value breaks the signature, like any other policy edit`() {
        val m = manifestWithPolicy("""{"enrollment":{"required":false}}""")
        val tampered = m.copy(policy = Json.parseToJsonElement("""{"enrollment":{"required":true}}""").jsonObject)
        assertFalse(verifyManifest(tampered, coursePubkeyHex))
    }

    @Test
    fun `the signed payload shape is unaffected by adding enrollment alongside capture`() {
        val withoutEnrollment = manifestWithPolicy("""{"capture":{"terminal":true}}""")
        val withEnrollment = manifestWithPolicy("""{"capture":{"terminal":true},"enrollment":{"required":false}}""")

        fun topLevelKeys(m: Manifest) =
            Json.parseToJsonElement(String(buildSignedPayload(m), Charsets.UTF_8)).jsonObject.keys

        // Same top-level key set either way: `policy` is one opaque key regardless of what
        // is inside it, so adding `enrollment` cannot introduce or remove a signed field.
        assertEquals(topLevelKeys(withoutEnrollment), topLevelKeys(withEnrollment))

        fun policyValue(m: Manifest) =
            Json.parseToJsonElement(String(buildSignedPayload(m), Charsets.UTF_8)).jsonObject["policy"]

        // Only the (opaque, verbatim) policy value itself differs.
        assertTrue(policyValue(withoutEnrollment) != policyValue(withEnrollment))
    }

    @Test
    fun `resolveEnrollmentPolicy reads the block riding inside a verified manifest's policy`() {
        val m = manifestWithPolicy("""{"enrollment":{"required":false}}""")
        assertFalse(resolveEnrollmentPolicy(m.policy).required)
    }
}
