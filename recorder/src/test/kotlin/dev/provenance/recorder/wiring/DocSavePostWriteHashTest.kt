package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Files
import java.nio.file.Path

/**
 * REGRESSION: doc.save must carry the hash of the bytes that reached DISK, never the hash of
 * the editor buffer at the moment the save started.
 *
 * The two are not the same string. IntelliJ mutates the document DURING the save, from inside
 * `FileDocumentManagerListener.beforeDocumentSaving` — that is where the platform's own
 * `TrailingSpacesStripper` (Settings → Editor → General → On Save) runs — and the stripped text
 * is what gets written. A recorder that hashes `document.text` in its own `beforeDocumentSaving`
 * hook therefore records a hash of content that never existed on disk, and the analyzer's
 * "submitted file matches the last recorded on-disk state" check (`verify-submitted-code.ts`)
 * fails against an honest student. Observed in a real submission: the recorded save hash was
 * exactly the submitted file plus the four spaces the stripper had removed.
 *
 * This is also why the trigger has to be the post-write VFS signal rather than any
 * `FileDocumentManagerListener` hook: `beforeDocumentSaving` fires before the physical write,
 * and there is no `afterDocumentSaving`. The reference implementation makes the same choice for
 * the same reason — see doc-wiring.ts, "We must use readFile rather than doc.getText()".
 *
 * The listener below stands in for the platform's stripper: it is registered the same way, does
 * the same thing (mutates the document in-place during the save), and pins the contract
 * independently of whether any particular IDE version/setting has stripping enabled.
 */
class DocSavePostWriteHashTest : BasePlatformTestCase() {

    private lateinit var wsRoot: Path
    private val saveObservations = mutableListOf<Pair<String, String>>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("docsave-ws")
        saveObservations.clear()
    }

    override fun tearDown() {
        try {
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private inner class FakeSink : RecordableSessionSink {
        override val workspaceRoot: Path get() = wsRoot
        override val pasteCorrelator: PasteCorrelator? get() = null
        override fun onDocOpen(payload: DocOpenPayload) = Unit
        override fun onDocChange(payload: DocChangePayload) = Unit
        override fun onSaveObserved(relativePath: String, onDiskContent: String) {
            saveObservations.add(relativePath to onDiskContent)
        }
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) = Unit
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    private fun install(): DocWiring {
        val sink = FakeSink()
        return DocWiring(
            project = project,
            router = SessionRouter { path -> if (path.startsWith(wsRoot)) sink else null },
            parentDisposable = testRootDisposable,
            // Synchronous dispatch: the production one hops to a pooled thread, which a test
            // cannot join without a sleep-and-hope. The work it dispatches is identical.
            vfsDispatch = { it() },
        )
    }

    /** Stand-in for the platform's TrailingSpacesStripper: mutates the document mid-save. */
    private fun installTrailingSpaceStripper() {
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val stripped = document.text.lines().joinToString("\n") { it.trimEnd() }
                    if (stripped != document.text) document.setText(stripped)
                }
            },
        )
    }

    private fun writeAndOpen(name: String, content: String): Document {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun save(doc: Document) = ApplicationManager.getApplication().invokeAndWait {
        WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
    }

    fun testDocSaveHashesWhatReachedDiskNotTheBufferAtSaveEntry() {
        installTrailingSpaceStripper()
        val doc = writeAndOpen("hw.py", "print(1)\n")
        install()

        // A line whose only content is indentation — exactly what auto-indent leaves behind and
        // what the stripper removes on save.
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "    \n") }
        val bufferAtSaveEntry = doc.text
        save(doc)

        val onDisk = Files.readString(wsRoot.resolve("hw.py"))
        assertTrue(
            "the stripper must actually have changed the bytes, or this test proves nothing",
            onDisk != bufferAtSaveEntry,
        )
        assertEquals("expected exactly one save observation", 1, saveObservations.size)
        val (path, observed) = saveObservations.single()
        assertEquals("hw.py", path)
        assertEquals("doc.save must carry the on-disk bytes", onDisk, observed)
        assertEquals(Sha256.hex(onDisk), Sha256.hex(observed))
    }
}
