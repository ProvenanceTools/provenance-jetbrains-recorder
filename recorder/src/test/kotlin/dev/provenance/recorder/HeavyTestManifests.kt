package dev.provenance.recorder

import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import dev.provenance.core.Manifest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Shared manifest helpers for the heavy end-to-end fixtures (real project open +
 * real production activation). Signs `.provenance-manifest` files with the DEV
 * course keypair whose public half is the embedded [COURSE_PUBLIC_KEY_HEX]
 * (`958d262b…`, seed in the session scratchpad dev-key.txt) — so a manifest built
 * here verifies against the real, unmodified activation gate, exactly as a genuine
 * course-signed manifest would.
 */
object HeavyTestManifests {
    /** DEV course private seed; its ed25519 pubkey == the embedded COURSE_PUBLIC_KEY_HEX. */
    private const val COURSE_PRIV_SEED_HEX =
        "e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75"

    /**
     * The exact JSON text of a valid, course-signed `.provenance-manifest` file — a
     * real ed25519 signature over JCS({assignment_id, semester, issued_at,
     * files_under_review}), the shape parseManifest/verifyManifest accept.
     */
    fun validManifestJson(
        assignmentId: String = "hw03",
        semester: String = "fa26",
        issuedAt: String = "2026-07-14T00:00:00Z",
        filesUnderReview: List<String> = listOf("hw.py"),
    ): String {
        val priv = Ed25519.hexToBytes(COURSE_PRIV_SEED_HEX)
        val payload = buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", semester)
            put("issued_at", issuedAt)
            putJsonArray("files_under_review") { filesUnderReview.forEach { add(it) } }
        }.toString()
        val sig = Ed25519.bytesToHex(
            Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), priv),
        )
        return buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", semester)
            put("issued_at", issuedAt)
            putJsonArray("files_under_review") { filesUnderReview.forEach { add(it) } }
            put("sig", sig)
        }.toString()
    }

    /**
     * The same valid, course-signed manifest as a parsed core [Manifest] object, for tests
     * that drive [dev.provenance.recorder.session.RecorderSessionManager.start] directly
     * (which takes an already-verified manifest rather than file text).
     */
    fun signedManifestObject(
        assignmentId: String = "hw03",
        semester: String = "fa26",
        issuedAt: String = "2026-07-14T00:00:00Z",
        filesUnderReview: List<String> = listOf("hw.py"),
    ): Manifest {
        val priv = Ed25519.hexToBytes(COURSE_PRIV_SEED_HEX)
        val payload = buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", semester)
            put("issued_at", issuedAt)
            putJsonArray("files_under_review") { filesUnderReview.forEach { add(it) } }
        }.toString()
        val sig = Ed25519.bytesToHex(
            Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), priv),
        )
        return Manifest(assignmentId, semester, issuedAt, filesUnderReview, sig)
    }

    /**
     * A structurally valid manifest whose signature does NOT verify against the
     * embedded course key (a foreign key signed it / it was tampered). parseManifest
     * succeeds; verifyManifest must reject it — the privacy gate's negative case.
     */
    fun invalidSignatureManifestJson(): String {
        // Sign with a DIFFERENT key so the 128-hex sig is well-formed but wrong.
        val foreignSeed = ByteArray(32) { 0x11 }
        val payload = buildJsonObject {
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", "2026-07-14T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val sig = Ed25519.bytesToHex(
            Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), foreignSeed),
        )
        return buildJsonObject {
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", "2026-07-14T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
            put("sig", sig)
        }.toString()
    }
}
