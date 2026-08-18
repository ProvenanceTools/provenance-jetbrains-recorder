package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path
import java.nio.file.Paths

class DocWiringTest : BasePlatformTestCase() {
    private val opens = mutableListOf<DocOpenPayload>()
    private val changes = mutableListOf<DocChangePayload>()
    private val saves = mutableListOf<DocSavePayload>()
    private val closes = mutableListOf<DocClosePayload>()

    private val workspaceRoot: Path = Paths.get("/ws")

    /** Kinds in emission order, so a test can assert doc.open precedes doc.change. */
    private val order = mutableListOf<String>()

    /** One entry per doc.open: was it emitted on the EDT? */
    private val openOnEdt = mutableListOf<Boolean>()

    private class FakeSink(
        override val workspaceRoot: Path,
        override val pasteCorrelator: PasteCorrelator? = null,
        val opens: MutableList<DocOpenPayload>,
        val changes: MutableList<DocChangePayload>,
        val saves: MutableList<DocSavePayload>,
        val closes: MutableList<DocClosePayload>,
        val order: MutableList<String> = mutableListOf(),
        val openOnEdt: MutableList<Boolean> = mutableListOf(),
    ) : RecordableSessionSink {
        override fun onDocOpen(payload: DocOpenPayload) {
            opens.add(payload)
            order.add("doc.open")
            openOnEdt.add(ApplicationManager.getApplication().isDispatchThread)
        }
        override fun onDocChange(payload: DocChangePayload) { changes.add(payload); order.add("doc.change") }
        override fun onDocSave(payload: DocSavePayload) { saves.add(payload); order.add("doc.save") }
        override fun onDocClose(payload: DocClosePayload) { closes.add(payload); order.add("doc.close") }
        override fun onPaste(payload: dev.provenance.core.PastePayload) = Unit
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    private fun fakeSink(pasteCorrelator: PasteCorrelator? = null) =
        FakeSink(workspaceRoot, pasteCorrelator, opens, changes, saves, closes, order, openOnEdt)

    /** Owns everything under /ws; nothing else. Mirrors a single active session. */
    private fun routerOwningWs(pasteCorrelator: PasteCorrelator? = null): SessionRouter {
        val sink = fakeSink(pasteCorrelator)
        return SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null }
    }

    private fun install(router: SessionRouter = routerOwningWs()) = DocWiring(
        project = project,
        router = router,
        parentDisposable = testRootDisposable,
        // Light-fixture files are not on the local FS; map them into the workspace by name.
        localFsOf = { true },
        nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
    )

    private fun document(): Document = myFixture.getDocument(myFixture.file)

    fun testDocOpenEmittedForAlreadyOpenFileViaCatchUp() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        assertEquals(1, opens.size)
        val open = opens[0]
        assertEquals("hw.py", open.path)
        assertEquals("print(1)\n", open.content)
        assertEquals(Sha256.hex("print(1)\n"), open.sha256)
    }

    fun testDocChangeEmitsSingleDeltaWithInsertedText() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(3, "X") }
        assertEquals(1, changes.size)
        val c = changes[0]
        assertEquals("hw.py", c.path)
        assertEquals("typed", c.source)
        assertEquals(1, c.deltas.size)
        assertEquals("X", c.deltas[0].text)
    }

    fun testDocChangeRangeIsPreChangeCoordinates() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.replaceString(0, 5, "say") }
        val d = changes[0].deltas[0]
        assertEquals(0L, d.range.start.line)
        assertEquals(0L, d.range.start.character)
        assertEquals(0L, d.range.end.line)
        assertEquals(5L, d.range.end.character)
        assertEquals("say", d.text)
    }

    fun testDocChangePreChangeCoordinatesOnSecondLine() {
        myFixture.configureByText("hw.py", "aaa\nbbb\n")
        install()
        val doc = document()
        // Insert on line 1 at char 2 (offset 6). Pre-change coords must be line=1,char=2.
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(6, "Z") }
        val d = changes.last().deltas[0]
        assertEquals(1L, d.range.start.line)
        assertEquals(2L, d.range.start.character)
        assertEquals("Z", d.text)
    }

    fun testDocSaveEmitsHashOfSavedContent() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        val finalText = doc.text
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(doc) }
        assertTrue("expected at least one save", saves.isNotEmpty())
        assertEquals(Sha256.hex(finalText), saves.last().sha256)
        assertEquals("hw.py", saves.last().path)
    }

    fun testNoOwningSessionEmitsNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        // Router with no owner at all — every path is dropped, exactly like a file outside
        // every assignment root.
        install(router = SessionRouter { null })
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }
        assertTrue(opens.isEmpty())
        assertTrue(changes.isEmpty())
    }

    fun testDocCloseEmittedOnFileClose() {
        myFixture.configureByText("hw.py", "print(1)\n")
        val vf: VirtualFile = myFixture.file.virtualFile
        install()
        FileEditorManager.getInstance(project).closeFile(vf)
        assertEquals(1, closes.size)
        assertEquals("hw.py", closes[0].path)
    }

    /**
     * Regression for the "IDE error occurred on every project open" bug: production constructs
     * DocWiring from RecorderSessionManager.startFromActivation, which the platform runs as a
     * coroutine on DefaultDispatcher-worker — NOT the EDT and with no read action. The catch-up
     * path calls FileDocumentManager.getDocument(), which does
     * ThreadingAssertions.softAssertReadAccess() → LOG.error. Under the IntelliJ test logger a
     * logged error fails the test, so driving construction from a pooled thread reproduces the
     * user-visible popup exactly.
     */
    fun testCatchUpFromBackgroundThreadTakesAReadAction() {
        myFixture.configureByText("hw.py", "print(1)\n")
        installFromPooledThread()
        assertEquals(1, opens.size)
        assertEquals("print(1)\n", opens[0].content)
    }

    /**
     * The ordering contract, recorder PRD §4.2.1: an already-open file's `doc.open` (the replay
     * baseline) must precede every `doc.change` for that file. The VS Code recorder gets this
     * from its host being single-threaded — `doc-wiring.ts` registers its subscriptions and then
     * walks `vscode.workspace.textDocuments` in the SAME synchronous turn, so no
     * `onDidChangeTextDocument` callback can slip between the two.
     *
     * IntelliJ's equivalent of "one uninterruptible turn" is the EDT: a document can only be
     * mutated inside a write action, and a write action can only run on the EDT. So the
     * registration + open-file enumeration + text snapshot + doc.open emission must ALL happen
     * in one EDT runnable. Driven from the activation coroutine (a background dispatcher, as
     * production does), a doc.open emitted off the EDT proves the window is open.
     */
    fun testCatchUpEmitsDocOpenOnTheEdtEvenWhenDrivenOffIt() {
        myFixture.configureByText("hw.py", "print(1)\n")
        installFromPooledThread()
        assertEquals(1, openOnEdt.size)
        assertTrue(
            "doc.open catch-up must run on the EDT — off it, a write action can land between " +
                "listener registration and the baseline read",
            openOnEdt[0],
        )
    }

    /**
     * The consequence of the above, made concrete: a write action that lands between listener
     * registration and the catch-up's document read gets logged as a `doc.change` BEFORE the
     * file's `doc.open`, and the baseline that doc.open then carries already contains the edit —
     * so replay applies the same delta twice and has no baseline for the first delta.
     *
     * The interleave is injected through the `nioPathOf` seam, which the catch-up calls once per
     * file before it reads the document. Off the EDT the queued write runs immediately (the EDT
     * is free); on the EDT it cannot run until the catch-up returns, so the latch times out and
     * the write lands after doc.open — which is the whole point.
     */
    fun testDocOpenPrecedesADocChangeThatRacesTheCatchUp() {
        myFixture.configureByText("hw.py", "print(1)\n")
        val doc = document()
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        val written = java.util.concurrent.CountDownLatch(1)
        val router = routerOwningWs()
        val future = ApplicationManager.getApplication().executeOnPooledThread {
            DocWiring(
                project = project,
                router = router,
                parentDisposable = testRootDisposable,
                localFsOf = { true },
                nioPathOf = { vf ->
                    if (armed.compareAndSet(true, false)) {
                        ApplicationManager.getApplication().invokeLater {
                            WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }
                            written.countDown()
                        }
                        written.await(1, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    workspaceRoot.resolve(vf.name)
                },
            )
        }
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 30_000)
        // Let the (possibly still queued) write action run before asserting.
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("doc.open must be the first event for the file", listOf("doc.open", "doc.change"), order)
        assertEquals(
            "doc.open must carry the PRE-change baseline; a post-change baseline double-applies the delta",
            "print(1)\n",
            opens[0].content,
        )
    }

    /** Production drives construction from RecorderSessionManager's activation coroutine — a
     * background dispatcher. The wait dispatches EDT events so the catch-up's EDT hop can run
     * (this test thread IS the EDT). */
    private fun installFromPooledThread() {
        val future = ApplicationManager.getApplication().executeOnPooledThread { install() }
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 30_000)
    }

    fun testTwoFilesWithTheSameRelativeNameInDifferentRootsBothGetDocOpen() {
        // Regression for the shared-listener de-dup bug: seenPaths must be keyed by absolute
        // path, not by relative path, or the second root's same-named file would be
        // (wrongly) treated as already-seen and silently dropped.
        val otherRoot = Paths.get("/ws-other")
        val vfA = myFixture.addFileToProject("a/hw.py", "print('a')\n").virtualFile
        val vfB = myFixture.addFileToProject("b/hw.py", "print('b')\n").virtualFile
        // DocWiring's doc.open catch-up only scans currently-open editors — addFileToProject
        // alone doesn't open one, so both files must be opened before wiring is installed.
        FileEditorManager.getInstance(project).openFile(vfA, false)
        FileEditorManager.getInstance(project).openFile(vfB, false)
        val sinkA = fakeSink()
        val opensB = mutableListOf<DocOpenPayload>()
        val sinkB = FakeSink(otherRoot, null, opensB, mutableListOf(), mutableListOf(), mutableListOf())
        val router = SessionRouter { path ->
            when {
                path.startsWith(workspaceRoot) -> sinkA
                path.startsWith(otherRoot) -> sinkB
                else -> null
            }
        }
        DocWiring(
            project = project,
            router = router,
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> if (vf == vfA) workspaceRoot.resolve("hw.py") else otherRoot.resolve("hw.py") },
        )
        assertEquals(1, opens.size)
        assertEquals(1, opensB.size)
    }
}
