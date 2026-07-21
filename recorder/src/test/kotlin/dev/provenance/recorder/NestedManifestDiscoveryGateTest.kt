package dev.provenance.recorder

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.provenance.recorder.activation.RecorderActivationActivity
import dev.provenance.recorder.activation.RecorderState
import dev.provenance.recorder.session.RecorderSessionManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * HEAVY end-to-end nested-discovery gate. A real on-disk project base dir holds two sibling
 * assignment subdirectories (`cats/`, `hog/`), each with its own valid course-signed
 * `.provenance-manifest`, plus a third sibling (`bad/`) with a foreign-key-signed one. Proves,
 * through the REAL production RecorderActivationActivity (recursive discovery + per-root
 * session start), that:
 *
 *  - two verified manifests ⇒ two live sessions, each with its own `.provenance/`;
 *  - the bad-signature sibling is skipped, never starts a session, writes nothing;
 *  - (regression) a single manifest at the project base dir still behaves exactly as the
 *    pre-discovery single-assignment path did — one session, no ambiguity.
 */
class NestedManifestDiscoveryGateTest : HeavyPlatformTestCase() {

    private fun baseDir(): VirtualFile = getOrCreateProjectBaseDir()

    private fun writeManifest(relDir: String, text: String) {
        WriteAction.runAndWait<RuntimeException> {
            val dir = if (relDir == ".") baseDir() else VfsUtil.createDirectoryIfMissing(baseDir(), relDir)
            val f = dir.findChild(".provenance-manifest") ?: dir.createChildData(this, ".provenance-manifest")
            VfsUtil.saveText(f, text)
        }
    }

    private fun runActivation() {
        runBlocking { RecorderActivationActivity().execute(project) }
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
        } finally {
            super.tearDown()
        }
    }

    fun testTwoSiblingManifestsStartTwoSessionsAndSkipTheBadOne() {
        writeManifest("cats", HeavyTestManifests.validManifestJson(assignmentId = "cats"))
        writeManifest("hog", HeavyTestManifests.validManifestJson(assignmentId = "hog"))
        writeManifest("bad", HeavyTestManifests.invalidSignatureManifestJson())

        runActivation()

        val state = project.service<RecorderState>()
        assertEquals(setOf("cats", "hog"), state.activeManifests.values.map { it.assignmentId }.toSet())

        val manager = project.service<RecorderSessionManager>()
        assertEquals("exactly two live sessions for the two verified siblings", 2, manager.activeSessions.size)

        val catsRoot = baseDir().toNioPath().resolve("cats").toRealPath()
        val hogRoot = baseDir().toNioPath().resolve("hog").toRealPath()
        val badRoot = baseDir().toNioPath().resolve("bad")

        assertNotNull("cats must have a live session", manager.activeSessions[catsRoot])
        assertNotNull("hog must have a live session", manager.activeSessions[hogRoot])
        assertTrue("cats/.provenance must exist", Files.isDirectory(catsRoot.resolve(".provenance")))
        assertTrue("hog/.provenance must exist", Files.isDirectory(hogRoot.resolve(".provenance")))
        assertFalse("bad/.provenance must never be created (bad signature ⇒ no session, no I/O)", Files.exists(badRoot.resolve(".provenance")))
    }

    fun testSingleManifestAtProjectBaseDirBehavesLikeBeforeDiscoveryExisted() {
        writeManifest(".", HeavyTestManifests.validManifestJson())
        // writeManifest(".", ...) writes directly under the base dir, mirroring the pre-
        // discovery single-manifest-at-workspace-root layout exactly.

        runActivation()

        val manager = project.service<RecorderSessionManager>()
        assertEquals("regression: exactly one session, no ambiguity", 1, manager.activeSessions.size)
        assertNotNull("the single-session back-compat accessor must resolve it", manager.activeSession)
        assertEquals("hw03", manager.activeSession!!.activated.manifest.assignmentId)
    }
}
