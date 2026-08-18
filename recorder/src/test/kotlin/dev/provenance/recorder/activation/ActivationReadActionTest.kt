package dev.provenance.recorder.activation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the read-lock invariant of activation discovery.
 *
 * [RecorderActivationActivity.execute] is a `ProjectActivity` coroutine, so it runs OFF the EDT
 * — where read access is never implicit. Everything the discoverer touches requires the read
 * lock: `ProjectRootManager.contentRoots` (`@RequiresReadLock(generateAssertion = false)` — a
 * contractual requirement whose assertion is deliberately suppressed, so violating it never
 * logs but CAN race a module-root write action and return an incomplete root set) and the VFS
 * walk / manifest read, which bottom out in `PersistentFSImpl` methods gated by a
 * `checkReadAccess()` that is a no-op only under the current default of
 * `vfs.read-access-check-kind`. Both failure modes are silent today: a verified assignment
 * simply isn't discovered and the student records nothing.
 *
 * The test therefore drives activation from a pooled thread (as production does) and asserts
 * the discoverer sees `isReadAccessAllowed`. Running it on the EDT would pass vacuously — the
 * EDT is always allowed to read.
 */
class ActivationReadActionTest : BasePlatformTestCase() {

    fun `test discovery runs under a read action when activation runs off the EDT`() {
        val onEdt = AtomicReference<Boolean>()
        val readAccessAllowed = AtomicReference<Boolean>()
        val activity = RecorderActivationActivity { _, _ ->
            onEdt.set(ApplicationManager.getApplication().isDispatchThread)
            readAccessAllowed.set(ApplicationManager.getApplication().isReadAccessAllowed)
            emptyList()
        }

        ApplicationManager.getApplication()
            .executeOnPooledThread { runBlocking { activity.execute(project) } }
            .get(60, TimeUnit.SECONDS)

        assertEquals("guard: this invariant is vacuous on the EDT", false, onEdt.get())
        assertEquals(
            "manifest discovery must hold the read lock for the whole walk " +
                "(contentRoots + VFS traversal + manifest read)",
            true,
            readAccessAllowed.get(),
        )
    }
}
