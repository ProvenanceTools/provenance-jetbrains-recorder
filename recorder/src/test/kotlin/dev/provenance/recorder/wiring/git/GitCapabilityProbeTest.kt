package dev.provenance.recorder.wiring.git

import dev.provenance.core.GitCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [probeGitCapture] and its reflective seam [isClassLoadable], tested as pure functions per
 * CLAUDE.md ("test the event-to-log-entry transformation as a pure function, separately from
 * the platform wiring") — no IntelliJ SDK, no fixture, no running IDE.
 */
class GitCapabilityProbeTest {
    @Test
    fun `probeGitCapture reports available when Git4Idea is classloadable`() {
        assertEquals(GitCaptureCapability.AVAILABLE, probeGitCapture { true })
    }

    @Test
    fun `probeGitCapture reports unavailable when Git4Idea is not classloadable`() {
        assertEquals(GitCaptureCapability.UNAVAILABLE, probeGitCapture { false })
    }

    @Test
    fun `probeGitCapture never reports not_owned`() {
        // See GitCapabilityProbe.kt's file docstring for exactly why: NOT_OWNED needs either a
        // reflective repository enumeration or cross-activity coordination that this task does
        // not add. Pinned here so a future change to this function's scope is a visible,
        // deliberate diff to this assertion, not a silent behavior change.
        for (loadable in listOf(true, false)) {
            assertTrue(probeGitCapture { loadable } != GitCaptureCapability.NOT_OWNED)
        }
    }

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
        // GitEventPayloadBuilderTest / RootCommitShaTest), so this is expected to be TRUE on
        // this module's test classpath — unlike a real IDE with Git4Idea genuinely absent, which
        // is what production `probeGitCapture` exists to detect. The point of this test is only
        // that the two functions agree and neither throws, not to fix a direction that depends
        // on this module's own Gradle wiring.
        assertEquals(isClassLoadable("git4idea.repo.GitRepositoryManager"), isGit4IdeaClassLoadable())
    }
}
