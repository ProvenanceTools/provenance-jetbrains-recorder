package dev.provenance.recorder.wiring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path
import java.nio.file.Paths

class SelectionWiringTest : BasePlatformTestCase() {
    private val workspaceRoot: Path = Paths.get("/ws")
    private val changes = mutableListOf<SelectionChangePayload>()

    private class FakeSink(override val workspaceRoot: Path, val changes: MutableList<SelectionChangePayload>) : RecordableSessionSink {
        override val pasteCorrelator: PasteCorrelator? = null
        override fun onDocOpen(payload: DocOpenPayload) = Unit
        override fun onDocChange(payload: DocChangePayload) = Unit
        override fun onDocSave(payload: DocSavePayload) = Unit
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) = Unit
        override fun onSelectionChange(payload: SelectionChangePayload) { changes.add(payload) }
    }

    fun testCaretMoveEmitsSelectionChangeForAnOwnedFile() {
        myFixture.configureByText("hw.py", "print(1)\nprint(2)\n")
        val sink = FakeSink(workspaceRoot, changes)
        SelectionWiring(
            router = SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
        val editor = myFixture.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.caretModel.moveToOffset(5) }
        assertTrue("expected at least one selection.change", changes.isNotEmpty())
        assertEquals("hw.py", changes.last().path)
    }

    fun testNoOwningSessionEmitsNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        SelectionWiring(
            router = SessionRouter { null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.caretModel.moveToOffset(3) }
        assertTrue(changes.isEmpty())
    }
}
