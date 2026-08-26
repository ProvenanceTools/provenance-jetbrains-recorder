package dev.provenance.recorder.activation

import dev.provenance.core.CourseCert
import dev.provenance.core.DEFAULT_ENROLLMENT_POLICY
import dev.provenance.core.Ed25519
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestCollaboration
import dev.provenance.core.ManifestScope
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.signCourseCert
import dev.provenance.core.signManifest
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveVerifiedEnrollmentPolicy] — the enrollment twin of the capture-policy
 * resolution [RecordingSessionController][dev.provenance.recorder.session.RecordingSessionController]
 * does inline via `resolveCapturePolicy(activated.manifest.policy)`. Unlike that
 * call site, this one is a standalone function: `RecorderState` needs the same
 * resolution as session start does (see its `identityOutcomes` getter), so it has
 * to be reachable without constructing a whole session.
 *
 * None of these manifests need a genuinely-signed `course_cert` — resolution reads
 * only `format_version` and `policy`, never verifies anything — except the two
 * tests that build a real 2.0 manifest end to end to prove the policy survives the
 * signed round trip, where a hand-wavy cert would prove nothing.
 */
class EnrollmentPolicyResolutionTest {

    private fun manifest(formatVersion: String?, policyJson: String?): Manifest = Manifest(
        assignmentId = "hw03",
        semester = "fa26",
        issuedAt = "2026-09-15T00:00:00Z",
        filesUnderReview = listOf("hw03.py"),
        sig = "a".repeat(128),
        formatVersion = formatVersion,
        policy = policyJson?.let { Json.parseToJsonElement(it).jsonObject },
    )

    @Test
    fun `a 2_0 manifest with no policy at all resolves to required`() {
        assertEquals(DEFAULT_ENROLLMENT_POLICY, resolveVerifiedEnrollmentPolicy(manifest("2.0", null)))
    }

    @Test
    fun `a 2_0 manifest with no enrollment key resolves to required`() {
        val m = manifest("2.0", """{"capture":{"terminal":true}}""")
        assertTrue(resolveVerifiedEnrollmentPolicy(m).required)
    }

    @Test
    fun `a 2_0 manifest with required false resolves to not required`() {
        val m = manifest("2.0", """{"enrollment":{"required":false}}""")
        assertFalse(resolveVerifiedEnrollmentPolicy(m).required)
    }

    @Test
    fun `a 2_0 manifest with required true resolves to required`() {
        val m = manifest("2.0", """{"enrollment":{"required":true}}""")
        assertTrue(resolveVerifiedEnrollmentPolicy(m).required)
    }

    @Test
    fun `a malformed non-boolean required value resolves to required`() {
        val m = manifest("2.0", """{"enrollment":{"required":"nope"}}""")
        assertTrue(resolveVerifiedEnrollmentPolicy(m).required)
    }

    /**
     * MANDATORY. `policy` is not part of the 1.x signed payload, so a 1.x manifest
     * must never be allowed to switch enrollment off — the same reasoning
     * `ManifestChain.NotManifest20` states for capture policy. This staples
     * `enrollment.required = false` onto a manifest whose `format_version` is
     * absent (i.e. 1.x by [dev.provenance.core.manifestFormatVersion]'s own
     * default), entirely bypassing the parser — which would never populate
     * `policy` for a 1.x manifest in the first place — to prove the function
     * itself refuses to read it, not merely that the parser happens not to hand it
     * one.
     */
    @Test
    fun `a 1x manifest with policy enrollment stapled on still resolves to required`() {
        val stapled = manifest(formatVersion = null, policyJson = """{"enrollment":{"required":false}}""")
        assertEquals(DEFAULT_ENROLLMENT_POLICY, resolveVerifiedEnrollmentPolicy(stapled))
        assertTrue(resolveVerifiedEnrollmentPolicy(stapled).required)
    }

    /** The same gate, for an explicit `"1.0"` rather than an absent field. */
    @Test
    fun `an explicit 1_0 manifest with policy enrollment stapled on still resolves to required`() {
        val stapled = manifest(formatVersion = "1.0", policyJson = """{"enrollment":{"required":false}}""")
        assertTrue(resolveVerifiedEnrollmentPolicy(stapled).required)
    }

    // -----------------------------------------------------------------------
    // End-to-end: a genuinely signed 2.0 manifest carrying the flag.
    // -----------------------------------------------------------------------

    @Test
    fun `a genuinely signed 2_0 manifest carrying required false resolves correctly after activation`() {
        val (coursePriv, coursePub) = Ed25519.generateKeypair()
        val (rootPriv, rootPub) = Ed25519.generateKeypair()
        val unsignedCert = CourseCert(
            courseId = "berkeley-cs61a",
            coursePubkey = Ed25519.bytesToHex(coursePub),
            validFrom = "2025-01-01",
            validUntil = "2027-01-01",
            rootSig = "",
        )
        val cert = unsignedCert.copy(rootSig = signCourseCert(unsignedCert, rootPriv))
        val unsigned = Manifest(
            assignmentId = "hw03",
            semester = "fa26",
            issuedAt = "2026-09-15T00:00:00Z",
            filesUnderReview = listOf("hw03.py"),
            sig = "",
            formatVersion = "2.0",
            courseId = "berkeley-cs61a",
            ignore = emptyList(),
            attachments = emptyList(),
            collaboration = ManifestCollaboration.SOLO,
            submission = ManifestSubmission.BUNDLE,
            scope = ManifestScope.DIRECTORY,
            policy = Json.parseToJsonElement("""{"enrollment":{"required":false}}""").jsonObject,
            courseCert = cert,
        )
        val signed = unsigned.copy(sig = signManifest(unsigned, coursePriv))

        val activation = evaluateManifestText(
            signed.toJsonObject().toString(),
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(rootPub),
        )
        assertTrue(activation is ManifestActivation.Active)
        val activated = (activation as ManifestActivation.Active).manifest
        assertFalse(resolveVerifiedEnrollmentPolicy(activated).required)
    }
}
