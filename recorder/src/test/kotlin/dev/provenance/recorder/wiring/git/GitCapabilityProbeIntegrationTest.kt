package dev.provenance.recorder.wiring.git

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.GitCaptureCapability
import git4idea.repo.GitRepositoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * [reflectiveGitRepositoryRoots] and [probeGitCapture] against a REAL `GitRepositoryManager`,
 * through `BasePlatformTestCase` — the same real-git harness [dev.provenance.recorder.GitExternalChangeGateTest]
 * already uses (`recorder/build.gradle.kts` pulls Git4Idea in as a `bundledPlugins` TEST
 * dependency).
 *
 * This is the test the file docstring in `GitCapabilityProbe.kt` points to as evidence that the
 * "cannot be validated without a running IDE with Git4Idea enabled" objection from the prior
 * pass does not hold: it can, and this proves the three reflected calls
 * (`GitRepositoryManager.getInstance`, `.getRepositories()`, `Repository.getRoot()`) actually
 * work against a genuine Git4Idea build, not merely against a hand-decompiled method signature.
 */
class GitCapabilityProbeIntegrationTest : BasePlatformTestCase() {
    private lateinit var ws: Path

    private fun git(dir: Path, vararg args: String) {
        val p = ProcessBuilder("git", *args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        val code = p.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} in $dir failed ($code):\n$out" }
    }

    private fun initRepo(dir: Path) {
        Files.createDirectories(dir)
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "test@provenance.test")
        git(dir, "config", "user.name", "Provenance Test")
        Files.writeString(dir.resolve("hw.py"), "print(1)\n")
        git(dir, "add", "hw.py")
        git(dir, "commit", "-m", "init")
    }

    private fun mapAndWaitForRepo(dir: Path) {
        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!)
        val mappings = ProjectLevelVcsManager.getInstance(project).directoryMappings
        ProjectLevelVcsManager.getInstance(project).directoryMappings =
            mappings + VcsDirectoryMapping(dir.toString(), "Git")
        val mgr = GitRepositoryManager.getInstance(project)
        val deadline = System.currentTimeMillis() + 20_000
        while (mgr.repositories.none { it.root.toNioPath() == dir } && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    override fun tearDown() {
        try {
            runCatching { ProjectLevelVcsManager.getInstance(project).directoryMappings = emptyList() }
            if (::ws.isInitialized) runCatching { ws.toFile().deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    fun testReflectiveGitRepositoryRootsSeesARealRepository() {
        ws = Files.createTempDirectory("git-capability-probe-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, ws.toString())
        val repo = ws.resolve("hw1")
        initRepo(repo)
        mapAndWaitForRepo(repo)

        val roots = reflectiveGitRepositoryRoots(project)
        assertTrue("the reflective probe must not fail against a real Git4Idea repository", roots != null)
        assertTrue(
            "the reflective probe must see the real, mapped repository's root",
            roots!!.any { it == repo },
        )
    }

    fun testProbeGitCaptureIsAvailableForTheOwningSession() {
        ws = Files.createTempDirectory("git-capability-probe-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, ws.toString())
        val repo = ws.resolve("hw1")
        initRepo(repo)
        mapAndWaitForRepo(repo)

        assertEquals(GitCaptureCapability.AVAILABLE, probeGitCapture(project, repo))
    }

    fun testProbeGitCaptureIsNotOwnedWhenOnlyASiblingRepositoryIsVisible() {
        ws = Files.createTempDirectory("git-capability-probe-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, ws.toString())
        val repoA = ws.resolve("61b/hw1")
        initRepo(repoA)
        mapAndWaitForRepo(repoA)

        // A second, concurrently-active assignment root that is NOT itself a git repository
        // and does not contain one — the reachable shape this task's investigation found:
        // nested-manifest discovery starts a session here too, and Git4Idea is plainly
        // available (it just proved repoA), but nothing routes to THIS session.
        val repoBSession = ws.resolve("61c/hw2")
        Files.createDirectories(repoBSession)

        assertEquals(GitCaptureCapability.NOT_OWNED, probeGitCapture(project, repoBSession))
    }

    fun testProbeGitCaptureIsAvailableWhenNoRepositoryIsMappedYet() {
        ws = Files.createTempDirectory("git-capability-probe-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, ws.toString())
        val noRepoYet = ws.resolve("hw1")
        Files.createDirectories(noRepoYet)

        assertFalse(
            "sanity: no VCS mapping means zero visible repositories",
            GitRepositoryManager.getInstance(project).repositories.isNotEmpty(),
        )
        assertEquals(GitCaptureCapability.AVAILABLE, probeGitCapture(project, noRepoYet))
    }
}
