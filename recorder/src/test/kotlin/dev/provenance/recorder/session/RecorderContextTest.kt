package dev.provenance.recorder.session

import dev.provenance.core.CourseCert
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestCollaboration
import dev.provenance.core.ManifestParse
import dev.provenance.core.ManifestScope
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.Sha256
import dev.provenance.core.parseManifestValue
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderContextTest {
    private fun manifest() = Manifest(
        assignmentId = "hw03",
        semester = "fa26",
        issuedAt = "2026-09-15T00:00:00Z",
        filesUnderReview = listOf("hw03.py"),
        sig = "ab".repeat(64),
    )

    @Test
    fun `buildRecorderContext produces the expected payload with fixed inputs`() {
        val p = buildRecorderContext(
            manifest = manifest(),
            prevSessionId = null,
            sessionId = "sess-1",
            sessionPubkeyHex = "d".repeat(64),
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            hostnameProvider = { "host-1" },
            usernameProvider = { "alice" },
        )
        assertEquals("1.0", p.formatVersion)
        assertEquals("sess-1", p.sessionId)
        assertNull(p.prevSessionId)
        assertEquals("hw03", p.assignmentId)
        assertEquals("fa26", p.assignmentSemester)
        assertEquals("ab".repeat(64), p.manifestSig)
        assertEquals("2026.1.4", p.vscodeVersion)
        assertEquals("", p.vscodeCommit)
        assertEquals("darwin-arm64", p.vscodePlatform)
        assertEquals("0.1.0", p.recorderVersion)
        assertEquals("com.aaryanmehta.provenance.recorder", p.recorderExtensionId)
        assertEquals("d".repeat(64), p.sessionPubkey)
        assertEquals(Sha256.hex("host-1:alice:sess-1"), p.machineId)
    }

    @Test
    fun `computeMachineId is deterministic and salted by each input`() {
        val base = computeMachineId("h", "u", "s")
        assertEquals(base, computeMachineId("h", "u", "s"))
        assertNotEquals(base, computeMachineId("h2", "u", "s"))
        assertNotEquals(base, computeMachineId("h", "u2", "s"))
        assertNotEquals(base, computeMachineId("h", "u", "s2"))
    }

    @Test
    fun `absent hostname falls back to unknown component`() {
        val p = buildRecorderContext(
            manifest = manifest(),
            prevSessionId = "prev",
            sessionId = "sess-2",
            sessionPubkeyHex = "d".repeat(64),
            ideVersion = "x",
            platform = "y",
            recorderVersion = "v",
            recorderExtensionId = "id",
            hostnameProvider = { null },
            usernameProvider = { "bob" },
        )
        assertEquals("prev", p.prevSessionId)
        assertEquals(Sha256.hex("unknown:bob:sess-2"), p.machineId)
    }

    // -----------------------------------------------------------------------
    // session.start 2.0 (program spec §5)
    // -----------------------------------------------------------------------

    private fun v2Manifest() = Manifest(
        assignmentId = "proj2",
        semester = "fa26",
        issuedAt = "2026-09-15T00:00:00Z",
        filesUnderReview = listOf("src/Main.java"),
        sig = "ab".repeat(64),
        formatVersion = "2.0",
        courseId = "berkeley-cs61b",
        collaboration = ManifestCollaboration.SOLO,
        submission = ManifestSubmission.BUNDLE,
        scope = ManifestScope.DIRECTORY,
        policy = Json.parseToJsonElement("""{"capture":{"terminal":false}}""").jsonObject,
        courseCert = CourseCert(
            courseId = "berkeley-cs61b",
            coursePubkey = "cd".repeat(32),
            validFrom = "2026-08-20",
            validUntil = "2027-01-15",
            rootSig = "ef".repeat(64),
        ),
    )

    private fun contextFor(manifest: Manifest) = buildRecorderContext(
        manifest = manifest,
        prevSessionId = null,
        sessionId = "sess-1",
        sessionPubkeyHex = "d".repeat(64),
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.aaryanmehta.provenance.recorder",
        hostnameProvider = { "host-1" },
        usernameProvider = { "alice" },
    )

    /** `host` is the un-warped replacement for the VS Code-shaped `vscode` block. */
    @Test
    fun `host block identifies jetbrains and mirrors the ide version and platform`() {
        val json = contextFor(v2Manifest()).toJsonObject()
        val host = json["host"]!!.jsonObject
        assertEquals("jetbrains", host["editor"]!!.jsonPrimitive.content)
        assertEquals("2026.1.4", host["editor_version"]!!.jsonPrimitive.content)
        assertEquals("darwin-arm64", host["platform"]!!.jsonPrimitive.content)
        // Present-and-empty, never absent: '' is permitted for editor_build.
        assertNotNull(host["editor_build"])
        assertEquals("", host["editor_build"]!!.jsonPrimitive.content)
    }

    /**
     * `vscode` is retained alongside `host` through the reader-before-writer migration
     * (program spec §9), so an analyzer that has not yet learned `host` keeps working.
     */
    @Test
    fun `vscode block is retained alongside host`() {
        val json = contextFor(v2Manifest()).toJsonObject()
        val vscode = json["vscode"]!!.jsonObject
        assertEquals("2026.1.4", vscode["version"]!!.jsonPrimitive.content)
        assertEquals("", vscode["commit"]!!.jsonPrimitive.content)
        assertEquals("darwin-arm64", vscode["platform"]!!.jsonPrimitive.content)
        // manifest_sig is retained too — it is what the session-key KDF binds to.
        assertEquals("ab".repeat(64), json["manifest_sig"]!!.jsonPrimitive.content)
        // The LOG format version is unchanged; 2.0 additions are additive optional fields.
        assertEquals("1.0", json["format_version"]!!.jsonPrimitive.content)
    }

    /**
     * The full manifest travels into the bundle and must survive the round trip — that is
     * what lets the analyzer walk root -> course -> manifest -> session offline instead of
     * only comparing manifest_sig across sessions for equality.
     */
    @Test
    fun `the full 2_0 manifest round-trips through session start`() {
        val original = v2Manifest()
        val emitted = contextFor(original).toJsonObject()["manifest"]!!
        val reparsed = parseManifestValue(emitted)
        assertTrue("emitted manifest must re-parse", reparsed is ManifestParse.Ok)
        assertEquals(original, (reparsed as ManifestParse.Ok).manifest)
        // The certificate and its window reach the analyzer intact: an expired cert does not
        // stop the recorder, so these are what let the analyzer re-run checkCertWindow.
        val cert = emitted.jsonObject["course_cert"]!!.jsonObject
        assertEquals("berkeley-cs61b", cert["course_id"]!!.jsonPrimitive.content)
        assertEquals("2027-01-15", cert["valid_until"]!!.jsonPrimitive.content)
        assertEquals("ef".repeat(64), cert["root_sig"]!!.jsonPrimitive.content)
    }

    /**
     * Emitted for 1.x manifests too. Additive, and a 1.x manifest's parsed form carries no
     * 2.0-only fields, so nothing unsigned can ride along.
     */
    @Test
    fun `a 1_x manifest is emitted too and carries no 2_0 fields`() {
        val emitted = contextFor(manifest()).toJsonObject()["manifest"]!!.jsonObject
        assertEquals("hw03", emitted["assignment_id"]!!.jsonPrimitive.content)
        assertEquals("ab".repeat(64), emitted["sig"]!!.jsonPrimitive.content)
        for (key in listOf("course_id", "collaboration", "submission", "scope", "policy", "course_cert")) {
            assertFalse("a 1.x manifest must not carry $key", key in emitted)
        }
    }

    /**
     * `identity` is OMITTED, never present-and-empty, when there is none to emit.
     *
     * This assertion changed meaning at S2: it used to say "enrollment does not exist yet".
     * It now states the real rule — an unenrolled student, which is the ordinary
     * pre-enrollment state, produces a payload with no `identity` key at all. The
     * enrolled case is covered in SessionIdentityBuilderTest, which can mint a real chain.
     */
    @Test
    fun `identity is omitted when there is none to emit`() {
        assertFalse("identity" in contextFor(v2Manifest()).toJsonObject())
        assertFalse("identity" in contextFor(manifest()).toJsonObject())
    }

    /** ...and present, verbatim, when one was assembled and chain-verified. */
    @Test
    fun `a supplied identity is emitted verbatim`() {
        val identity = dev.provenance.core.SessionIdentity(
            enrollment = dev.provenance.recorder.identity.EnrollmentFixtures.token(),
            enrollmentCert = dev.provenance.recorder.identity.EnrollmentFixtures.cert(),
            sessionPubkeySig = "ab".repeat(64),
        )
        val json = buildRecorderContext(
            manifest = v2Manifest(),
            prevSessionId = null,
            sessionId = "sess-1",
            sessionPubkeyHex = "d".repeat(64),
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            hostnameProvider = { "host-1" },
            usernameProvider = { "alice" },
            identity = identity,
        ).toJsonObject()

        assertEquals(identity.toJsonObject(), json["identity"])
        assertEquals(
            setOf("enrollment", "enrollment_cert", "session_pubkey_sig"),
            json["identity"]!!.jsonObject.keys,
        )
    }
}
