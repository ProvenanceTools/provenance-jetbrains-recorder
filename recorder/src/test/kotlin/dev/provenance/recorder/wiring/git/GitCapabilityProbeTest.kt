package dev.provenance.recorder.wiring.git

import com.intellij.openapi.project.Project
import dev.provenance.core.GitCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [probeGitCapture], [decideGitCapture] and the reflective seams [isClassLoadable] /
 * [isGit4IdeaClassLoadable], tested as pure functions per CLAUDE.md ("test the
 * event-to-log-entry transformation as a pure function, separately from the platform wiring")
 * — no IntelliJ SDK fixture, no running IDE.
 *
 * [fakeProject] stands in for the one constructor argument [probeGitCapture] cannot avoid
 * taking (it is threaded straight through to the injected [probeGitCapture] `repositoryRootsOf`
 * seam, which every test here overrides). `Project` is a platform interface with no concrete
 * no-arg implementation available outside a running IDE; a [Proxy] that fails any invocation
 * proves the value is genuinely never called when the seam is overridden — the real reflective
 * adapter ([reflectiveGitRepositoryRoots]) is covered separately in
 * [GitCapabilityProbeIntegrationTest], against a real project and a real Git4Idea repository.
 */
class GitCapabilityProbeTest {
    private fun fakeProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            throw AssertionError(
                "probeGitCapture must not touch Project directly when repositoryRootsOf is " +
                    "overridden; unexpectedly called Project.${method.name}",
            )
        } as Project

    private fun path(s: String): Path = Paths.get(s)

    // -------------------------------------------------------------------------------------
    // probeGitCapture: the classloadable gate and the omission rule.
    // -------------------------------------------------------------------------------------

    @Test
    fun `probeGitCapture reports unavailable when Git4Idea is not classloadable`() {
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/hw1"),
            git4IdeaClassLoadable = { false },
            repositoryRootsOf = { error("must not be called when Git4Idea is not classloadable") },
        )
        assertEquals(GitCaptureCapability.UNAVAILABLE, result)
    }

    @Test
    fun `probeGitCapture omits the field when the reflective repository read itself fails`() {
        // null from repositoryRootsOf means "the reflective probe could not answer", distinct
        // from an empty list ("answered: zero repositories"). Omission, never a guess.
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/hw1"),
            git4IdeaClassLoadable = { true },
            repositoryRootsOf = { null },
        )
        assertNull(result)
    }

    @Test
    fun `probeGitCapture reports available when no repository is visible yet`() {
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/hw1"),
            git4IdeaClassLoadable = { true },
            repositoryRootsOf = { emptyList() },
        )
        assertEquals(GitCaptureCapability.AVAILABLE, result)
    }

    @Test
    fun `probeGitCapture reports available when the session's own repository is visible`() {
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/hw1"),
            git4IdeaClassLoadable = { true },
            repositoryRootsOf = { listOf(path("/ws/hw1")) },
        )
        assertEquals(GitCaptureCapability.AVAILABLE, result)
    }

    @Test
    fun `probeGitCapture reports not_owned when every visible repository belongs to a sibling session`() {
        // The reachable shape this task's investigation confirmed: two concurrently-active,
        // nested-manifest-discovered assignment roots in one project, one of them a git repo,
        // the other not. See GitCapabilityProbe.kt's file docstring.
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/61c/hw2"),
            git4IdeaClassLoadable = { true },
            repositoryRootsOf = { listOf(path("/ws/61b/hw1")) },
        )
        assertEquals(GitCaptureCapability.NOT_OWNED, result)
    }

    @Test
    fun `probeGitCapture reports available when only one of several visible repositories is owned`() {
        val result = probeGitCapture(
            fakeProject(),
            path("/ws/61b/hw1"),
            git4IdeaClassLoadable = { true },
            repositoryRootsOf = { listOf(path("/ws/61c/hw2"), path("/ws/61b/hw1")) },
        )
        assertEquals(GitCaptureCapability.AVAILABLE, result)
    }

    // -------------------------------------------------------------------------------------
    // decideGitCapture: the pure ownership predicate, isolated from Project/Git4Idea entirely.
    // -------------------------------------------------------------------------------------

    @Test
    fun `decideGitCapture is available for zero repositories`() {
        assertEquals(GitCaptureCapability.AVAILABLE, decideGitCapture(path("/ws/hw1"), emptyList()))
    }

    @Test
    fun `decideGitCapture is available when the session root equals a repository root`() {
        assertEquals(
            GitCaptureCapability.AVAILABLE,
            decideGitCapture(path("/ws/hw1"), listOf(path("/ws/hw1"))),
        )
    }

    @Test
    fun `decideGitCapture is available when a repository is nested under the session root`() {
        // The submodule / nested-repository case D12's root_commit_sha exists to disambiguate:
        // the assignment root itself owns a repository beneath it.
        assertEquals(
            GitCaptureCapability.AVAILABLE,
            decideGitCapture(path("/ws/hw1"), listOf(path("/ws/hw1/vendor/lib"))),
        )
    }

    @Test
    fun `decideGitCapture is not_owned when the only visible repository is a sibling`() {
        assertEquals(
            GitCaptureCapability.NOT_OWNED,
            decideGitCapture(path("/ws/hw2"), listOf(path("/ws/hw1"))),
        )
    }

    @Test
    fun `decideGitCapture is available when the only visible repository is the session root's parent`() {
        // A repository whose root sits ABOVE the assignment root is the "standard nested
        // course layout" shape (one shared class repo, assignment as a subdirectory) —
        // decision-log bug 3's shape. RecorderSessionManager.sessionsOwningRepo now routes
        // such a repository's git.event to this session (see its KDoc), so decideGitCapture
        // must agree: reporting NOT_OWNED here while the router actually delivers the event
        // would contradict the live wiring.
        assertEquals(
            GitCaptureCapability.AVAILABLE,
            decideGitCapture(path("/ws/hw1"), listOf(path("/ws"))),
        )
    }

    @Test
    fun `decideGitCapture is not_owned when the only visible repository is an unrelated directory`() {
        // Neither an ancestor nor a descendant of the session root — a sibling elsewhere in the
        // tree, the case NOT_OWNED exists to defend.
        assertEquals(
            GitCaptureCapability.NOT_OWNED,
            decideGitCapture(path("/ws/hw1"), listOf(path("/ws/hw2/vendor"))),
        )
    }

    @Test
    fun `decideGitCapture is available when at least one of several repositories is owned`() {
        assertEquals(
            GitCaptureCapability.AVAILABLE,
            decideGitCapture(path("/ws/hw1"), listOf(path("/ws/hw2"), path("/ws/hw1"))),
        )
    }

    // -------------------------------------------------------------------------------------
    // isClassLoadable / isGit4IdeaClassLoadable — unchanged behavior, kept from before.
    // -------------------------------------------------------------------------------------

    @Test
    fun `isClassLoadable is true for a real JDK class`() {
        assertTrue(isClassLoadable("java.lang.String"))
    }

    @Test
    fun `isClassLoadable is false for a class name that does not exist`() {
        assertFalse(isClassLoadable("dev.provenance.does.not.Exist"))
    }

    @Test
    fun `isGit4IdeaClassLoadable agrees with isClassLoadable on the real class name`() {
        // recorder/build.gradle.kts pulls in Git4Idea as a `bundledPlugins` test dependency (for
        // GitEventPayloadBuilderTest / RootCommitShaTest / GitCapabilityProbeIntegrationTest),
        // so this is expected to be TRUE on this module's test classpath — unlike a real IDE
        // with Git4Idea genuinely absent, which is what production `probeGitCapture` exists to
        // detect. The point of this test is only that the two functions agree and neither
        // throws, not to fix a direction that depends on this module's own Gradle wiring.
        assertEquals(isClassLoadable("git4idea.repo.GitRepositoryManager"), isGit4IdeaClassLoadable())
    }

    @Test
    fun `reflectiveGitRepositoryRoots fails closed to null rather than throw`() {
        // git4idea.repo.GitRepositoryManager is genuinely on this module's test classpath (see
        // isGit4IdeaClassLoadable's test above), so this exercises the REAL reflected class —
        // just handed a Project it cannot actually use (fakeProject()'s proxy throws on every
        // method call). Whatever GitRepositoryManager.getInstance does internally with that
        // Project, the result is a thrown exception, and reflectiveGitRepositoryRoots must turn
        // that into `null`, never propagate it. The genuine, successful reflective read against
        // a real project and a real repository is covered in GitCapabilityProbeIntegrationTest.
        assertNull(reflectiveGitRepositoryRoots(fakeProject()))
    }
}
