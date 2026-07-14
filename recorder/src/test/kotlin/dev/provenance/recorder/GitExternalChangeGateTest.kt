package dev.provenance.recorder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.ChainCheck
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.ParseResult
import dev.provenance.core.Sha256
import dev.provenance.core.parseEntries
import dev.provenance.core.validateChain
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.wiring.git.GitWiringStartupActivity
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * REAL-git external-change gate (PRD §4.5, the checkout/reset suppression case). Drives a
 * genuine Git4Idea repository through a real `git checkout` that rewrites a reviewed file,
 * and proves the live recorder emits BOTH a `git.event` (our GitWiringStartupActivity's
 * GIT_REPO_CHANGE subscription firing on the real repository state change) AND the resulting
 * `fs.external_change` carrying `explanation="git"` — i.e. the git operation suppresses what
 * would otherwise read as an unexplained external edit.
 *
 * This is strictly stronger than AllSignalsLiveGateTest, which marks the git seam by calling
 * the emit callback directly: here the git.event originates from a real Git4Idea repository
 * update, not a synthetic call. Git4Idea is bundled (build.gradle bundledPlugins("Git4Idea"))
 * and a real `git` binary drives the working tree; the ProjectActivity that registers the
 * subscription is not auto-run in tests (SDK FAQ), so it is invoked directly.
 *
 * Determinism: the session clock is a FixedClock, so the ExplanationTagger window never
 * expires between the git.event mark and the fs.external_change consume — the git explanation
 * is deterministic regardless of real wall time. Repository detection (async in git4idea) is
 * awaited by a bounded poll, not a fixed sleep.
 */
class GitExternalChangeGateTest : BasePlatformTestCase() {
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

    private lateinit var ws: Path
    private val otherBranchContent = "print(1)\nprint(2)\nimport external\n"

    private fun git(vararg args: String) {
        val cmd = arrayOf("git", *args)
        val p = ProcessBuilder(*cmd).directory(ws.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        val code = p.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code):\n$out" }
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            runCatching { ProjectLevelVcsManager.getInstance(project).directoryMappings = emptyList() }
            runCatching { ws.toFile().deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    private fun waitForRepo(mgr: GitRepositoryManager) {
        val deadline = System.currentTimeMillis() + 20_000
        while (mgr.repositories.isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    fun testRealGitCheckoutEmitsGitEventAndGitExplainedExternalChange() {
        ws = Files.createTempDirectory("git-external-change-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, ws.toString())

        // Real repo: main has print(1); a sibling branch "other" rewrites hw.py. End on main.
        git("init", "-b", "main")
        git("config", "user.email", "test@provenance.test")
        git("config", "user.name", "Provenance Test")
        Files.writeString(ws.resolve("hw.py"), "print(1)\n")
        git("add", "hw.py"); git("commit", "-m", "init")
        git("checkout", "-b", "other")
        Files.writeString(ws.resolve("hw.py"), otherBranchContent)
        git("commit", "-am", "other")
        git("checkout", "main")

        // Map the working tree to Git and wait for git4idea to detect the repository.
        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ws)!!)
        ProjectLevelVcsManager.getInstance(project).directoryMappings =
            listOf(VcsDirectoryMapping(ws.toString(), "Git"))
        val mgr = GitRepositoryManager.getInstance(project)
        waitForRepo(mgr)
        assertFalse("git4idea must detect the real repository", mgr.repositories.isEmpty())

        // Register our git.event subscription (ProjectActivity is not auto-run in tests).
        runBlocking { GitWiringStartupActivity().execute(project) }

        val hw = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ws.resolve("hw.py"))!!
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(hw, true)
        }

        val manager = project.service<RecorderSessionManager>()
        val session = manager.start(
            activated = ActivatedWorkspace(HeavyTestManifests.signedManifestObject(), ws.resolve(".provenance"), ws),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "edu.berkeley.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )
        // Sync the editor buffer to disk so the branch checkout below is a genuine
        // memory-clean external content change (not a memory/disk conflict).
        val doc = FileDocumentManager.getInstance().getDocument(hw)!!
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }

        // THE REAL GIT OPERATION: checkout the other branch → rewrites hw.py on disk.
        git("checkout", "other")
        // Force git4idea to observe the branch/HEAD change (off the EDT) → fires GIT_REPO_CHANGE
        // → our subscription emits git.event and marks the ExplanationTagger.
        ApplicationManager.getApplication().executeOnPooledThread { mgr.repositories[0].update() }.get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Surface the new file content to VFS → fs.external_change, tagged explanation="git".
        VfsUtil.markDirtyAndRefresh(false, false, false, hw)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        session.controller.flush()
        val entries: List<HashedEnvelope> =
            (parseEntries(Files.readString(session.controller.slogPath)) as ParseResult.Ok).entries

        assertTrue(
            "a real Git4Idea repository state change must produce at least one git.event",
            entries.count { it.kind == "git.event" } >= 1,
        )
        val ext = entries.filter { it.kind == "fs.external_change" }
        assertEquals("the checkout must produce exactly one fs.external_change for hw.py", 1, ext.size)
        assertEquals(
            "a git-driven external change must carry explanation=\"git\" (PRD §4.5 checkout suppression)",
            "git",
            ext[0].data["explanation"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "the external change hash must be the checked-out branch content",
            Sha256.hex(otherBranchContent),
            ext[0].data["new_hash"]?.jsonPrimitive?.content,
        )
        assertEquals("the chain must stay valid across the real git-driven signals", ChainCheck.Valid, validateChain(entries))
    }
}
