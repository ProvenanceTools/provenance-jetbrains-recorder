package dev.provenance.recorder.activation

import dev.provenance.core.Canonical
import dev.provenance.core.CourseCert
import dev.provenance.core.Ed25519
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestCollaboration
import dev.provenance.core.ManifestScope
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.signCourseCert
import dev.provenance.core.signManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestActivationTest {

    /**
     * Hand-roll a signed manifest using core's Ed25519/Canonical primitives.
     * The signed payload is JCS({assignment_id, semester, issued_at, files_under_review}),
     * matching core's buildSignedPayload (the sig field is excluded before signing).
     */
    private fun signedManifestJson(assignmentId: String = "hw03", privkey: ByteArray): String {
        val payload = Canonical.canonicalize(
            """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"]}""",
        )
        val sig = Ed25519.bytesToHex(Ed25519.sign(payload.toByteArray(Charsets.UTF_8), privkey))
        return """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"],"sig":"$sig"}"""
    }

    @Test
    fun `valid signature yields Active with the parsed manifest`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
        assertEquals("hw03", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    @Test
    fun `wrong pubkey yields Inactive signature_invalid`() {
        val (priv, _) = Ed25519.generateKeypair()
        val (_, otherPub) = Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, Ed25519.bytesToHex(otherPub))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `malformed json yields Inactive parse_error, never throws`() {
        val result = evaluateManifestText("not json", "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("parse_error", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `well-formed but tampered field yields Inactive signature_invalid`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val text = signedManifestJson(assignmentId = "hw03", privkey = priv)
            .replace("\"hw03\"", "\"hw04\"") // tamper after signing
        val result = evaluateManifestText(text, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }

    // -----------------------------------------------------------------------
    // Manifest 2.0 routing (program spec §2, §3)
    // -----------------------------------------------------------------------

    private val policy = Json.parseToJsonElement("""{"capture":{"terminal":false}}""").jsonObject

    /** A complete, correctly-signed 2.0 manifest as file text. */
    private fun signedV2ManifestJson(
        coursePriv: ByteArray,
        rootPriv: ByteArray,
        courseId: String = "dev-course",
        certCourseId: String = courseId,
        issuedAt: String = "2026-09-15T00:00:00Z",
        validFrom: String = "2025-01-01",
        validUntil: String = "2027-01-01",
    ): String {
        val unsignedCert = CourseCert(
            courseId = certCourseId,
            coursePubkey = Ed25519.bytesToHex(Ed25519.publicKeyOf(coursePriv)),
            validFrom = validFrom,
            validUntil = validUntil,
            rootSig = "",
        )
        val cert = unsignedCert.copy(rootSig = signCourseCert(unsignedCert, rootPriv))
        val manifest = Manifest(
            assignmentId = "hw03",
            semester = "fa26",
            issuedAt = issuedAt,
            filesUnderReview = listOf("hw03.py"),
            sig = "",
            formatVersion = "2.0",
            courseId = courseId,
            collaboration = ManifestCollaboration.SOLO,
            submission = ManifestSubmission.BUNDLE,
            scope = ManifestScope.DIRECTORY,
            policy = policy,
            courseCert = cert,
        )
        val sig = signManifest(manifest, coursePriv)
        return buildJsonObject {
            put("format_version", "2.0")
            put("course_id", courseId)
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", issuedAt)
            putJsonArray("files_under_review") { add("hw03.py") }
            put("collaboration", "solo")
            put("submission", "bundle")
            put("scope", "directory")
            put("policy", policy)
            put(
                "course_cert",
                buildJsonObject {
                    put("course_id", cert.courseId)
                    put("course_pubkey", cert.coursePubkey)
                    put("valid_from", cert.validFrom)
                    put("valid_until", cert.validUntil)
                    put("root_sig", cert.rootSig)
                },
            )
            put("sig", sig)
        }.toString()
    }

    @Test
    fun `a 2_0 manifest chain-verifies against the root key`() {
        val (coursePriv, _) = Ed25519.generateKeypair()
        val (rootPriv, rootPub) = Ed25519.generateKeypair()
        val text = signedV2ManifestJson(coursePriv, rootPriv)
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(rootPub),
        )
        assertTrue(result is ManifestActivation.Active)
        assertEquals("hw03", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    @Test
    fun `a 2_0 manifest under the wrong root key does not activate`() {
        val (coursePriv, _) = Ed25519.generateKeypair()
        val (rootPriv, _) = Ed25519.generateKeypair()
        val (_, otherRootPub) = Ed25519.generateKeypair()
        val text = signedV2ManifestJson(coursePriv, rootPriv)
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(otherRootPub),
        )
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("chain_invalid_root_signature", (result as ManifestActivation.Inactive).reason)
    }

    /**
     * Routing, not fallback. The legacy key here IS the key that signed the payload,
     * so a fallback implementation would activate. It must not: falling back would
     * restore exactly the downgrade that the chain's step 0 exists to close, since
     * at 1.x the policy block is outside the signed payload.
     */
    @Test
    fun `a 2_0 manifest is never retried against the legacy course key`() {
        val (coursePriv, coursePub) = Ed25519.generateKeypair()
        val (rootPriv, _) = Ed25519.generateKeypair()
        val (_, otherRootPub) = Ed25519.generateKeypair()
        val text = signedV2ManifestJson(coursePriv, rootPriv)
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = Ed25519.bytesToHex(coursePub),
            rootPubkeyHex = Ed25519.bytesToHex(otherRootPub),
        )
        assertTrue(result is ManifestActivation.Inactive)
    }

    @Test
    fun `a course_id the certificate does not cover does not activate`() {
        val (coursePriv, _) = Ed25519.generateKeypair()
        val (rootPriv, rootPub) = Ed25519.generateKeypair()
        val text = signedV2ManifestJson(
            coursePriv,
            rootPriv,
            courseId = "berkeley-cs61c",
            certCourseId = "berkeley-cs61b",
        )
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(rootPub),
        )
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("chain_course_id_mismatch", (result as ManifestActivation.Inactive).reason)
    }

    /**
     * Program spec §4: an expired certificate must NOT stop the recorder from
     * recording. Silently halting capture for a whole class is a worse failure for
     * an integrity tool than recording under a stale key; the analyzer decides.
     */
    @Test
    fun `an expired certificate still activates`() {
        val (coursePriv, _) = Ed25519.generateKeypair()
        val (rootPriv, rootPub) = Ed25519.generateKeypair()
        val text = signedV2ManifestJson(
            coursePriv,
            rootPriv,
            issuedAt = "2026-09-15T00:00:00Z",
            validFrom = "2024-01-01",
            validUntil = "2024-12-31",
        )
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(rootPub),
        )
        assertTrue(result is ManifestActivation.Active)
    }

    /**
     * The 1.x path is not reached by the root key. Signing with a key and offering
     * it only as the ROOT anchor must not activate — that is the whole point of
     * grandfathering the legacy key as a separate, explicitly-1.x-only anchor.
     */
    @Test
    fun `a 1_x manifest is not verified against the root key`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(
            text,
            legacyCoursePubkeyHex = "a".repeat(64),
            rootPubkeyHex = Ed25519.bytesToHex(pub),
        )
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }

    /** The shipped defaults route a real dev-key-signed 1.x manifest to Active. */
    @Test
    fun `the embedded legacy anchor verifies a 1_x manifest by default`() {
        // provjet's own DEV course seed; its pubkey is the embedded
        // LEGACY_COURSE_PUBLIC_KEY_HEX, unchanged in value from pre-2.0 `main`.
        val devCourseSeed =
            Ed25519.hexToBytes("e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75")
        assertEquals(
            LEGACY_COURSE_PUBLIC_KEY_HEX,
            Ed25519.bytesToHex(Ed25519.publicKeyOf(devCourseSeed)),
        )
        val result = evaluateManifestText(signedManifestJson(privkey = devCourseSeed))
        assertTrue(result is ManifestActivation.Active)
    }
}
