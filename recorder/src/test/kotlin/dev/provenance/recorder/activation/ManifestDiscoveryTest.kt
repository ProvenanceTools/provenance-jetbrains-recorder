package dev.provenance.recorder.activation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

class ManifestDiscoveryTest : BasePlatformTestCase() {

    private fun signedManifestJson(priv: ByteArray, assignmentId: String): String {
        val payload = buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", "fa26")
            put("issued_at", "2026-09-15T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val canon = Canonical.canonicalize(payload)
        val sig = Ed25519.bytesToHex(Ed25519.sign(canon.toByteArray(Charsets.UTF_8), priv))
        return buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", "fa26")
            put("issued_at", "2026-09-15T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
            put("sig", sig)
        }.toString()
    }

    fun `test finds a single manifest at the search root`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val dir = myFixture.addFileToProject("proj/.provenance-manifest", signedManifestJson(priv, "hw01")).virtualFile.parent
        val found = discoverManifestRoots(listOf(dir), Ed25519.bytesToHex(pub))
        assertEquals(1, found.size)
        assertEquals("hw01", found[0].manifest.assignmentId)
        assertEquals(dir, found[0].root)
    }

    fun `test finds two sibling nested manifests and skips a bad-signature sibling`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val (foreignPriv, _) = Ed25519.generateKeypair()
        val base = myFixture.addFileToProject("course/cats/.provenance-manifest", signedManifestJson(priv, "cats")).virtualFile.parent.parent
        myFixture.addFileToProject("course/hog/.provenance-manifest", signedManifestJson(priv, "hog"))
        myFixture.addFileToProject("course/bad/.provenance-manifest", signedManifestJson(foreignPriv, "bad"))

        val found = discoverManifestRoots(listOf(base), Ed25519.bytesToHex(pub))

        val assignmentIds = found.map { it.manifest.assignmentId }.toSet()
        assertEquals(setOf("cats", "hog"), assignmentIds)
    }

    fun `test returns empty list when nothing verifies`() {
        val (_, pub) = Ed25519.generateKeypair()
        val dir = myFixture.addFileToProject("empty/unrelated.txt", "nothing").virtualFile.parent
        assertTrue(discoverManifestRoots(listOf(dir), Ed25519.bytesToHex(pub)).isEmpty())
    }

    fun `test does not descend into pruned directories`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val base = myFixture.addFileToProject("proj2/marker.txt", "x").virtualFile.parent
        myFixture.addFileToProject("proj2/node_modules/pkg/.provenance-manifest", signedManifestJson(priv, "sneaky"))
        val found = discoverManifestRoots(listOf(base), Ed25519.bytesToHex(pub))
        assertTrue("manifests under a pruned dir name must never be discovered", found.isEmpty())
    }

    fun `test finds a manifest nested inside an already-found manifest directory`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val outer = myFixture.addFileToProject("nest/.provenance-manifest", signedManifestJson(priv, "outer")).virtualFile.parent
        myFixture.addFileToProject("nest/inner/.provenance-manifest", signedManifestJson(priv, "inner"))
        val found = discoverManifestRoots(listOf(outer), Ed25519.bytesToHex(pub))
        assertEquals(setOf("outer", "inner"), found.map { it.manifest.assignmentId }.toSet())
    }
}
