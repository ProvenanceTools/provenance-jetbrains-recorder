package dev.provenance.recorder.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording the student sees after importing a credential into a LIVE session.
 *
 * Pure, so the one thing that must never drift — the admission that work recorded BEFORE the
 * import stays unattributed and cannot be retrofitted — is pinned without the platform. A
 * notification that let a student believe their earlier work had just been covered would be
 * worse than the silence this whole change replaces.
 */
class EnrollmentRestartNoticeTest {

    @Test
    fun `with nothing recording, the student is told only about future sessions`() {
        val notice = identityStoredNotice("Your identity for berkeley is stored.", activeRoots = 0)
        assertTrue(notice, notice.contains("Your identity for berkeley is stored."))
        assertTrue(notice, notice.contains("New recording sessions"))
        assertFalse("nothing is restarting, so do not say it is", notice.contains("restart"))
    }

    @Test
    fun `with sessions live, the student is told recording is restarting`() {
        val notice = identityStoredNotice("Stored.", activeRoots = 2)
        assertTrue(notice, notice.contains("restart"))
        assertTrue("the count matters — the student sees which assignments blink", notice.contains("2"))
    }

    @Test
    fun `the restart notice never implies earlier work is now covered`() {
        val notice = identityStoredNotice("Stored.", activeRoots = 1)
        assertTrue(notice, notice.contains("before"))
        assertTrue("earlier work stays unattributed and must be said plainly", notice.contains("unattributed"))
        assertTrue(notice, notice.contains("cannot"))
    }

    @Test
    fun `a failed restart tells the student to reopen the project, not that all is well`() {
        val notice = restartFailedNotice(failedRoots = 1)
        assertTrue(notice, notice.contains("reopen"))
        assertTrue("the credential DID store — say so, or they will import it again", notice.contains("stored"))
        assertTrue("still honest about the earlier work", notice.contains("unattributed"))
    }

    @Test
    fun `the failure notice pluralises rather than reading like a template`() {
        assertTrue(restartFailedNotice(1).contains("1 assignment"))
        assertTrue(restartFailedNotice(3).contains("3 assignments"))
    }
}
