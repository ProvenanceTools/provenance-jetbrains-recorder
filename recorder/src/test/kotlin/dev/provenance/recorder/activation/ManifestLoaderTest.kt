package dev.provenance.recorder.activation

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519

class ManifestLoaderTest : BasePlatformTestCase() {

    private fun signedManifestJson(privkey: ByteArray, assignmentId: String = "hw03"): String {
        val payload = Canonical.canonicalize(
            """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"]}""",
        )
        val sig = Ed25519.bytesToHex(Ed25519.sign(payload.toByteArray(Charsets.UTF_8), privkey))
        return """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"],"sig":"$sig"}"""
    }

    /** Materialize a file in the fixture and return the directory that contains it. */
    private fun baseDirWith(name: String, text: String): VirtualFile =
        myFixture.addFileToProject(name, text).virtualFile.parent

    fun `test returns Inactive no_manifest_file when neither name exists`() {
        // A directory that holds an unrelated file but neither manifest name.
        val baseDir = baseDirWith("unrelated.txt", "nothing to see")
        val result = loadAndVerifyManifest(baseDir, "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("no_manifest_file", (result as ManifestActivation.Inactive).reason)
    }

    fun `test returns Active for a valid dotfile manifest`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val baseDir = baseDirWith(".provenance-manifest", signedManifestJson(priv))
        val result = loadAndVerifyManifest(baseDir, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
    }

    fun `test prefers dotfile over plain name when both present`() {
        val (priv, pub) = Ed25519.generateKeypair()
        myFixture.addFileToProject("provenance-manifest", signedManifestJson(priv, assignmentId = "plain"))
        val baseDir = baseDirWith(".provenance-manifest", signedManifestJson(priv, assignmentId = "dot"))
        val result = loadAndVerifyManifest(baseDir, Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
        assertEquals("dot", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    fun `test project overload returns Inactive when project dir has no manifest`() {
        val result = loadAndVerifyManifest(project, "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
    }
}
