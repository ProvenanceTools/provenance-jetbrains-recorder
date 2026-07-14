package dev.provenance.recorder.wiring

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
import dev.provenance.core.Sha256
import java.nio.file.Path
import java.nio.file.Paths

class DocWiringTest : BasePlatformTestCase() {
    private val opens = mutableListOf<DocOpenPayload>()
    private val changes = mutableListOf<DocChangePayload>()
    private val saves = mutableListOf<DocSavePayload>()
    private val closes = mutableListOf<DocClosePayload>()

    private val workspaceRoot: Path = Paths.get("/ws")
    private val provenanceDir: Path = Paths.get("/ws/.provenance")

    private fun install() = DocWiring(
        project = project,
        provenanceDir = provenanceDir,
        workspaceRoot = workspaceRoot,
        emitDocOpen = { opens.add(it) },
        emitDocChange = { changes.add(it) },
        emitDocSave = { saves.add(it) },
        emitDocClose = { closes.add(it) },
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
        // Replace [0,5) ("print") with "say" — the recorded range must be the PRE-change span.
        WriteCommandAction.runWriteCommandAction(project) { doc.replaceString(0, 5, "say") }
        assertEquals(1, changes.size)
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
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().saveDocument(doc)
        }
        assertTrue("expected at least one save", saves.isNotEmpty())
        assertEquals(Sha256.hex(finalText), saves.last().sha256)
        assertEquals("hw.py", saves.last().path)
    }

    fun testFilesNotRecordableEmitNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        // Non-recordable: force isLocalFs false.
        DocWiring(
            project, provenanceDir, workspaceRoot,
            { opens.add(it) }, { changes.add(it) }, { saves.add(it) }, { closes.add(it) },
            testRootDisposable,
            localFsOf = { false },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
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
}
