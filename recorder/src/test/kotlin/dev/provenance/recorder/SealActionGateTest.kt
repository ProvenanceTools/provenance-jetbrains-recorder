package dev.provenance.recorder

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.recorder.commands.PrepareSubmissionBundleAction
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.startup.RecoveryDecision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * Seal UI-trigger gate (PRD §4.6 / §5.3). Proves the "Provenance: Prepare Submission
 * Bundle" AnAction end to end:
 *
 *  - its enablement gate: disabled with no active session, enabled once one is live;
 *  - invoking [PrepareSubmissionBundleAction.actionPerformed] on a live session actually
 *    produces a sealed bundle ZIP on disk (the action's background-task path, not just
 *    the sealActiveSession() seam the all-signals gate covers).
 *
 * The produced bundle is copied to a stable build path and validated separately by the
 * real monorepo analysis-core (loadBundle + runValidation → 8/8), exactly as the existing
 * gates do — proving the action's output is analyzer-accepted, not merely well-formed.
 */
class SealActionGateTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(other: java.util.concurrent.Delayed?) = 0
            }
    }

    private lateinit var wsRoot: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("seal-action-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot.toString())
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        Files.writeString(wsRoot.resolve(name), content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot.resolve(name))!!
    }

    private fun actionEvent(action: PrepareSubmissionBundleAction) =
        TestActionEvent.createTestEvent(action) { dataId ->
            if (CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }

    private fun startLiveSession(): RecorderSessionManager {
        val vf = vfFor("hw.py", "print(1)\n")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        val manager = project.service<RecorderSessionManager>()
        manager.start(
            activated = ActivatedWorkspace(HeavyTestManifests.signedManifestObject(), wsRoot.resolve(".provenance"), wsRoot),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )
        // A real edit + save so the on-disk hw.py matches the event-reconstructed content
        // (check 8 submitted_code_match), yielding a genuine 8/8 bundle.
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
        return manager
    }

    fun testActionDisabledWithoutActiveSession() {
        val action = PrepareSubmissionBundleAction()
        val e = actionEvent(action)
        action.update(e)
        assertFalse("no active session ⇒ the seal action is disabled/hidden", e.presentation.isEnabledAndVisible)
    }

    fun testActionPerformedSealsAnalyzerReadyBundle() {
        startLiveSession()

        val action = PrepareSubmissionBundleAction()
        val enableEvent = actionEvent(action)
        action.update(enableEvent)
        assertTrue("a live session ⇒ the seal action is enabled", enableEvent.presentation.isEnabledAndVisible)

        // Invoke the real UI trigger. In unit-test mode Task.Backgroundable runs synchronously.
        action.actionPerformed(actionEvent(action))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val bundles = Files.list(wsRoot).use { s ->
            s.filter { it.fileName.toString().matches(Regex("hw03-bundle-.*\\.zip")) }.toList()
        }
        assertEquals("the seal action must produce exactly one bundle zip", 1, bundles.size)
        assertTrue("bundle must be a non-empty file", Files.size(bundles[0]) > 0)

        // Stash for the separate node analysis-core validation (loadBundle + runValidation).
        val outDir = Paths.get("build/e2e-seal-action")
        Files.createDirectories(outDir)
        val stable = outDir.resolve(bundles[0].fileName.toString())
        Files.copy(bundles[0], stable, StandardCopyOption.REPLACE_EXISTING)
        println("E2E_SEAL_ACTION_BUNDLE_PATH=" + stable.toAbsolutePath())
    }
}
