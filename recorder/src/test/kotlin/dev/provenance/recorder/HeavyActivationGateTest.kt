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
 * HEAVY end-to-end activation gate (PRD §4.1) — a REAL on-disk project is opened by
 * HeavyPlatformTestCase, a real `.provenance-manifest` is written into its base dir,
 * and the REAL production [RecorderActivationActivity] (with its real VFS-backed
 * manifest loader + real ed25519 verify) is executed against that project. This proves
 * the whole activation decision + session bring-up path headlessly, which the prior
 * pass could only verify by hand in a runIde sandbox:
 *
 *  - a valid course-signed manifest ⇒ RecorderState activates, RecorderSessionManager
 *    starts a live session, `.provenance/` + a `session-*.slog` appear on disk;
 *  - no manifest, and an invalid-signature manifest ⇒ NO activation, NO session,
 *    NO `.provenance/` directory, no I/O (the privacy gate holds).
 *
 * ProjectActivity is not auto-awaited in tests (SDK testing FAQ), so the activity is
 * invoked directly — the same call the platform makes on project open, exercising the
 * identical production code path.
 */
class HeavyActivationGateTest : HeavyPlatformTestCase() {

    private fun baseDir(): VirtualFile = getOrCreateProjectBaseDir()

    private fun writeManifest(text: String) {
        WriteAction.runAndWait<RuntimeException> {
            val dir = baseDir()
            val f = dir.findChild(".provenance-manifest") ?: dir.createChildData(this, ".provenance-manifest")
            VfsUtil.saveText(f, text)
        }
    }

    private fun runActivation() {
        runBlocking { RecorderActivationActivity().execute(project) }
    }

    // Resolve against the materialized base dir directly (guessProjectDir() is null until the
    // base dir exists, and on the negative paths activation never touches it).
    private fun provenanceDirPath() = baseDir().toNioPath().resolve(".provenance")

    fun testValidCourseSignedManifestActivatesAndStartsRealSession() {
        writeManifest(HeavyTestManifests.validManifestJson())

        runActivation()

        val state = project.service<RecorderState>()
        assertTrue("valid manifest must activate the privacy gate", state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)

        val manager = project.service<RecorderSessionManager>()
        val session = manager.activeSession
        assertNotNull("a live recording session must have started on activation", session)

        // Real files: .provenance/ dir + a session-*.slog created by the real controller/writer.
        val provDir = provenanceDirPath()
        assertTrue(".provenance/ must exist on disk", Files.isDirectory(provDir))

        session!!.controller.flush()
        val slogs = Files.list(provDir).use { s -> s.filter { it.fileName.toString().matches(Regex("session-.*\\.slog")) }.toList() }
        assertTrue("exactly one session-*.slog must have been created", slogs.size == 1)
        val text = Files.readString(slogs[0])
        assertTrue("the log must open with a session.start entry", text.contains("\"session.start\""))
    }

    fun testNoManifestDoesNotActivateAndWritesNothing() {
        // No manifest written.
        runActivation()

        assertFalse("no manifest ⇒ gate stays inactive", project.service<RecorderState>().isActive)
        assertNull("no manifest ⇒ no session", project.service<RecorderSessionManager>().activeSession)
        assertFalse("no manifest ⇒ no .provenance/ directory (no I/O)", Files.exists(provenanceDirPath()))
    }

    fun testInvalidSignatureManifestDoesNotActivateAndWritesNothing() {
        writeManifest(HeavyTestManifests.invalidSignatureManifestJson())

        runActivation()

        assertFalse("bad signature ⇒ gate stays inactive (PRD §4.1)", project.service<RecorderState>().isActive)
        assertNull("bad signature ⇒ no session", project.service<RecorderSessionManager>().activeSession)
        assertFalse("bad signature ⇒ no .provenance/ directory (no I/O)", Files.exists(provenanceDirPath()))
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
        } finally {
            super.tearDown()
        }
    }
}
