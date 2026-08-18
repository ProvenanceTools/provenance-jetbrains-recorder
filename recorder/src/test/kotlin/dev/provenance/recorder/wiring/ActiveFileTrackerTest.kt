package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `session.heartbeat.active_file` must be servable from the heartbeat's background scheduler
 * thread without touching the platform: the tracker reads FileEditorManager exactly once, on
 * the EDT, and is thereafter fed by FileEditorManagerListener.selectionChanged (which the
 * platform already delivers on the EDT).
 */
class ActiveFileTrackerTest : BasePlatformTestCase() {

    // The FileEditorWithProvider-based replacement constructor needs a real FileEditor; this
    // test only needs the newFile, so the (deprecated) explicit-field constructor is used.
    @Suppress("DEPRECATION")
    private fun publishSelection(newFile: VirtualFile?) {
        project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
            .selectionChanged(
                FileEditorManagerEvent(
                    FileEditorManager.getInstance(project),
                    null, null, null,
                    newFile, null, null,
                ),
            )
    }

    fun testSeedsFromTheSelectedFileExactlyOnceAndOnlyOnTheEdt() {
        val reads = AtomicInteger(0)
        val readThreads = mutableListOf<String>()
        val tracker = ActiveFileTracker(
            project = project,
            parentDisposable = testRootDisposable,
            readSelectedFileName = {
                reads.incrementAndGet()
                synchronized(readThreads) { readThreads.add(Thread.currentThread().name) }
                "hw.py"
            },
        )
        assertEquals("hw.py", tracker.activeFileName())
        assertEquals(1, reads.get())

        // Simulate 5 heartbeat ticks from a background thread: they must NOT re-read the
        // platform — that is the whole point of the cache.
        val seen = AtomicReference<String?>(null)
        ApplicationManager.getApplication().executeOnPooledThread {
            repeat(5) { seen.set(tracker.activeFileName()) }
        }.get(30, TimeUnit.SECONDS)

        assertEquals("hw.py", seen.get())
        assertEquals("the platform must be read only at seed time", 1, reads.get())
        assertTrue("seed must run on the EDT, got ${readThreads.first()}", readThreads.first().contains("AWT-EventQueue"))
    }

    fun testSelectionChangedUpdatesTheCachedName() {
        val tracker = ActiveFileTracker(
            project = project,
            parentDisposable = testRootDisposable,
            readSelectedFileName = { null },
        )
        assertNull(tracker.activeFileName())

        val vf = myFixture.addFileToProject("a/hw.py", "print(1)\n").virtualFile
        publishSelection(vf)
        assertEquals("hw.py", tracker.activeFileName())

        // Closing the last editor selects nothing.
        publishSelection(null)
        assertNull(tracker.activeFileName())
    }

    fun testSubscriptionIsTornDownWithItsDisposable() {
        val disposable = Disposer.newDisposable(testRootDisposable, "active-file-tracker-test")
        val tracker = ActiveFileTracker(
            project = project,
            parentDisposable = disposable,
            readSelectedFileName = { null },
        )
        val vf = myFixture.addFileToProject("a/hw.py", "print(1)\n").virtualFile
        publishSelection(vf)
        assertEquals("hw.py", tracker.activeFileName())

        Disposer.dispose(disposable)

        val other = myFixture.addFileToProject("b/other.py", "print(2)\n").virtualFile
        publishSelection(other)
        assertEquals("no updates after the session Disposable is disposed", "hw.py", tracker.activeFileName())
    }

    fun testSeedIsDeferredToTheEdtWhenConstructedOffTheEdt() {
        val pending = mutableListOf<Runnable>()
        val tracker = ApplicationManager.getApplication().executeOnPooledThread<ActiveFileTracker> {
            ActiveFileTracker(
                project = project,
                parentDisposable = testRootDisposable,
                readSelectedFileName = { "hw.py" },
                onEdt = { r -> synchronized(pending) { pending.add(r) } },
            )
        }.get(30, TimeUnit.SECONDS)

        assertNull("nothing is read off the EDT during construction", tracker.activeFileName())
        assertEquals(1, pending.size)
        pending.single().run() // what invokeLater would do, on the EDT
        assertEquals("hw.py", tracker.activeFileName())
    }
}
