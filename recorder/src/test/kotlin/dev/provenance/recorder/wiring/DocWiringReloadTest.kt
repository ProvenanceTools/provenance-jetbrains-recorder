package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.ResolvedScope
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.watch.ExternalChangeCoordinator
import dev.provenance.recorder.watch.relativePathOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression for the false-accusation bug: a disk reload (git pull / git stash pop / an
 * external tool writing a file IntelliJ has open with a clean buffer) drives
 * DocumentListener.documentChanged with one big whole-buffer delta. PasteClassifier calls
 * any single insert >= PASTE_MIN_INSERT_CHARS "paste-likely", so the recorder logged the
 * OTHER student's pulled work as a `paste` by this student — while the very same change
 * was also (correctly) logged as `fs.external_change`.
 *
 * Uses a REAL LocalFileSystem temp dir, not the light fixture's in-memory documents: only
 * a real file can be written behind IntelliJ's back and reloaded through
 * FileDocumentManager.reloadFromDisk, which is what makes the buffer converge to disk while
 * staying SAVED — the discriminator the fix keys on.
 */
class DocWiringReloadTest : BasePlatformTestCase() {
    private val opens = mutableListOf<DocOpenPayload>()
    private val changes = mutableListOf<DocChangePayload>()
    private val pastes = mutableListOf<PastePayload>()
    private val external = mutableListOf<FsExternalChangePayload>()

    private lateinit var tempDir: Path
    private var now = 0L

    private val originalContent = "public class ArrayDeque<T> {\n}\n"

    /**
     * Verbatim shape of the reported bug: the partner's work APPENDED by a git pull. The
     * append matters — DocumentImpl trims the common prefix/suffix, so this arrives as one
     * pure INSERT, which is the shape PasteCorrelator turns into a full `paste` event (the
     * `seq 301 paste src/ArrayDeque.java 492 chars` line in the submitted bundle).
     */
    private val pulledContent =
        "public class ArrayDeque<T> {\n" +
            "    private T[] items;\n" +
            "    private int size;\n" +
            "    public ArrayDeque() { items = (T[]) new Object[8]; size = 0; }\n" +
            "}\n"

    /**
     * The other reload shape: content REPLACED rather than appended (git stash pop, a
     * force-checkout). It arrives as one big replacement, which the correlator downgrades to
     * a `doc.change` with `source: "paste_likely"` — still a false accusation, just a
     * quieter one.
     */
    private val rewrittenContent =
        "import java.util.*;\npublic final class ArrayDeque<T> implements Deque<T> { /* rewritten */ }\n"

    private class FakeSink(
        override val workspaceRoot: Path,
        override val pasteCorrelator: PasteCorrelator?,
        val opens: MutableList<DocOpenPayload>,
        val changes: MutableList<DocChangePayload>,
        val pastes: MutableList<PastePayload>,
    ) : RecordableSessionSink {
        override fun onDocOpen(payload: DocOpenPayload) { opens.add(payload) }
        override fun onDocChange(payload: DocChangePayload) { changes.add(payload) }
        override fun onSaveObserved(relativePath: String, onDiskContent: String) = Unit
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) { pastes.add(payload) }
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("docwiring-reload-ws")
        VfsRootAccess.allowRootAccess(testRootDisposable, tempDir.toRealPath().toString())
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = tempDir.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    /** The VFS's own idea of the root, so router matching and relativization agree with it. */
    private fun rootOf(vf: VirtualFile): Path = vf.parent!!.toNioPath()

    /** Installs the PRODUCTION DocWiring seams (real local-FS check, real nio paths). */
    private fun install(root: Path) {
        val sink = FakeSink(root, PasteCorrelator(getNow = { now }), opens, changes, pastes)
        DocWiring(
            project = project,
            router = SessionRouter { path -> if (path.startsWith(root)) sink else null },
            parentDisposable = testRootDisposable,
        )
    }

    private fun openInEditor(vf: VirtualFile) = ApplicationManager.getApplication().invokeAndWait {
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun documentOf(vf: VirtualFile): Document =
        FileDocumentManager.getInstance().getDocument(vf)!!

    private fun save(doc: Document) = ApplicationManager.getApplication().invokeAndWait {
        WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
    }

    /** Write behind IntelliJ's back, then make it reload the clean buffer — a `git pull`. */
    private fun reloadFromDisk(vf: VirtualFile, newContent: String) {
        val doc = documentOf(vf)
        Files.writeString(tempDir.resolve(vf.name), newContent)
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(doc) }
        }
    }

    fun testReloadFromDiskIsNeverRecordedAsAPaste() {
        val vf = vfFor("ArrayDeque.java", originalContent)
        openInEditor(vf)
        install(rootOf(vf))

        reloadFromDisk(vf, pulledContent)

        assertEquals("the buffer must actually have reloaded", pulledContent, documentOf(vf).text)
        assertTrue(
            "a git-pull reload must NEVER be logged as a paste by the student: $pastes",
            pastes.isEmpty(),
        )
        assertTrue(
            "a git-pull reload must not be logged as a doc.change either: $changes",
            changes.isEmpty(),
        )
    }

    /** The replacement-shaped reload — downgraded to `source: "paste_likely"`, equally false. */
    fun testReloadThatRewritesTheBufferIsNotRecordedAsAPasteLikelyDocChange() {
        val vf = vfFor("ArrayDeque.java", originalContent)
        openInEditor(vf)
        install(rootOf(vf))

        reloadFromDisk(vf, rewrittenContent)

        assertEquals("the buffer must actually have reloaded", rewrittenContent, documentOf(vf).text)
        assertTrue("no paste: $pastes", pastes.isEmpty())
        assertTrue("no doc.change, of any source: $changes", changes.isEmpty())
    }

    fun testWatchedFileReloadStillYieldsExactlyOneFsExternalChange() {
        val vf = vfFor("ArrayDeque.java", originalContent)
        val root = rootOf(vf)
        val rel = relativePathOf(vf, root)!!
        openInEditor(vf)
        install(root)
        val coordinator = ExternalChangeCoordinator(
            project = project,
            workspaceRoot = root,
            scope = ResolvedScope(track = listOf(rel), ignore = emptyList(), attachments = emptyList()),
            emit = { external.add(it) },
            vfsDispatch = { it() },
        )
        Disposer.register(testRootDisposable, coordinator)
        coordinator.start()

        reloadFromDisk(vf, pulledContent)

        assertEquals("the change must still be recorded, exactly once: $external", 1, external.size)
        assertEquals("modify", external[0].operation)
        assertTrue("and never as a paste: $pastes", pastes.isEmpty())
        assertTrue("and never as a doc.change: $changes", changes.isEmpty())
    }

    fun testGenuineLargePasteStillEmitsPaste() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        val payload = "y".repeat(40)
        val doc = documentOf(vf)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(3, payload) }

        assertEquals("a real in-editor large insert must still be a paste", 1, pastes.size)
        assertEquals(payload, pastes[0].content)
        assertTrue("and must not be double-logged as a doc.change", changes.isEmpty())
    }

    fun testTypedEditStillEmitsDocChange() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        val doc = documentOf(vf)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(3, "X") }

        assertEquals(1, changes.size)
        assertEquals("typed", changes[0].source)
        assertEquals("X", changes[0].deltas[0].text)
        assertTrue(pastes.isEmpty())
    }

    /**
     * THE most dangerous regression this guard could introduce, and the reason the VS Code
     * reference does NOT key on the dirty flag alone: VS Code delivers the content-change
     * event BEFORE it flips `document.isDirty`, so a student's first edit on a clean buffer
     * (just opened, or just saved) looks exactly like a reload. Dropping it would silently
     * lose a keystroke and corrupt replay — strictly worse than the fabricated paste.
     *
     * This pins IntelliJ's ordering: the first edit after a save must still be recorded.
     */
    fun testFirstEditAfterASaveIsStillRecorded() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        val doc = documentOf(vf)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "a\n") }
        save(doc)
        assertFalse("the buffer must be clean before the next edit", FileDocumentManager.getInstance().isDocumentUnsaved(doc))
        changes.clear()
        pastes.clear()

        // The very first keystroke on the now-clean buffer.
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }

        assertEquals("the first edit after a save must still be recorded: $changes", 1, changes.size)
        assertEquals("typed", changes[0].source)
        assertEquals("Z", changes[0].deltas[0].text)
    }

    /**
     * The platform canary behind the guard, probed directly so a future IDE version that
     * changes the ordering fails HERE with an obvious diagnostic rather than as a silently
     * dropped keystroke somewhere in DocWiring.
     *
     * Pins that `isDocumentUnsaved` is a valid reload discriminator at `documentChanged` time:
     * for a genuine in-editor edit on a CLEAN buffer it must already read `true` (the platform
     * marks the document unsaved before downstream listeners run), while for a
     * `reloadFromDisk` — which the platform runs inside an `ExternalChangeAction` write action —
     * it must read `false`. VS Code has no such guarantee: it delivers the change event before
     * flipping `isDirty`, which is why doc-wiring.ts must fall back to a synchronous disk read.
     */
    fun testIsDocumentUnsavedDiscriminatesEditFromReloadAtDocumentChangedTime() {
        val vf = vfFor("hw.py", originalContent)
        openInEditor(vf)
        val doc = documentOf(vf)
        val fdm = FileDocumentManager.getInstance()
        val observed = mutableListOf<Boolean>()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (fdm.getFile(event.document) == vf) observed.add(fdm.isDocumentUnsaved(event.document))
                }
            },
            testRootDisposable,
        )

        assertFalse("precondition: buffer starts clean", fdm.isDocumentUnsaved(doc))
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }
        assertEquals(
            "a genuine edit on a clean buffer must already read UNSAVED at documentChanged time — " +
                "if this is false, the isDocumentUnsaved guard would drop real keystrokes",
            listOf(true),
            observed,
        )

        observed.clear()
        save(doc)
        reloadFromDisk(vf, pulledContent)
        assertTrue("the reload must have fired documentChanged", observed.isNotEmpty())
        assertFalse(
            "a reload-from-disk must read SAVED at documentChanged time — that is what makes it " +
                "distinguishable from an edit; observed=$observed",
            observed.contains(true),
        )
    }

    /** Same hazard, paste-sized: a large paste onto a freshly-saved clean buffer. */
    fun testFirstLargePasteAfterASaveIsStillRecorded() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        val doc = documentOf(vf)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "a\n") }
        save(doc)
        changes.clear()
        pastes.clear()

        val payload = "q".repeat(40)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, payload) }

        assertEquals("the first paste after a save must still be recorded: $pastes", 1, pastes.size)
        assertEquals(payload, pastes[0].content)
    }

    /** And on a freshly-opened, never-edited buffer — the other "clean buffer" entry point. */
    fun testFirstEditOnAFreshlyOpenedBufferIsStillRecorded() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        val doc = documentOf(vf)
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc))
        WriteCommandAction.runWriteCommandAction(project) { doc.deleteString(0, 5) }

        assertEquals("the first edit on a freshly-opened buffer must be recorded: $changes", 1, changes.size)
        assertEquals("typed", changes[0].source)
    }

    /**
     * The pending-range lifecycle: `beforeDocumentChange` stores a range for EVERY change,
     * reload included. A skipped reload must still clear it, and the next genuine edit must
     * be logged with ITS OWN pre-change coordinates.
     */
    fun testTypedEditAfterASkippedReloadIsRecordedWithItsOwnRange() {
        val vf = vfFor("hw.py", "print(1)\n")
        openInEditor(vf)
        install(rootOf(vf))

        reloadFromDisk(vf, pulledContent)
        assertTrue(changes.isEmpty())
        assertTrue(pastes.isEmpty())

        val doc = documentOf(vf)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }

        assertEquals("exactly one doc.change, for the typed edit only", 1, changes.size)
        val d = changes[0].deltas[0]
        assertEquals("Z", d.text)
        assertEquals(0L, d.range.start.line)
        assertEquals(0L, d.range.start.character)
        assertEquals(0L, d.range.end.line)
        assertEquals(0L, d.range.end.character)
    }
}
