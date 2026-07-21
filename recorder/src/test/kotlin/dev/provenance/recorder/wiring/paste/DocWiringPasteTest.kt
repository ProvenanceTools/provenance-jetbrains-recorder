package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.RecordableSessionSink
import dev.provenance.recorder.wiring.SessionRouter
import java.nio.file.Path
import java.nio.file.Paths

class DocWiringPasteTest : BasePlatformTestCase() {
    private val changes = mutableListOf<DocChangePayload>()
    private val pastes = mutableListOf<PastePayload>()
    private val workspaceRoot: Path = Paths.get("/ws")
    private var now = 0L
    private lateinit var correlator: PasteCorrelator

    private class FakeSink(
        override val workspaceRoot: Path,
        override val pasteCorrelator: PasteCorrelator?,
        val changes: MutableList<DocChangePayload>,
        val pastes: MutableList<PastePayload>,
    ) : RecordableSessionSink {
        override fun onDocOpen(payload: DocOpenPayload) = Unit
        override fun onDocChange(payload: DocChangePayload) { changes.add(payload) }
        override fun onDocSave(payload: DocSavePayload) = Unit
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) { pastes.add(payload) }
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    private fun install() {
        correlator = PasteCorrelator(getNow = { now })
        val sink = FakeSink(workspaceRoot, correlator, changes, pastes)
        DocWiring(
            project = project,
            router = SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
    }

    private fun document(): Document = myFixture.getDocument(myFixture.file)

    fun testTypedSmallInsertEmitsExactlyOneDocChangeAndNoPaste() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        WriteCommandAction.runWriteCommandAction(project) { document().insertString(3, "X") }
        assertEquals(1, changes.size)
        assertEquals("typed", changes[0].source)
        assertTrue(pastes.isEmpty())
    }

    fun testLargePasteShapedInsertEmitsExactlyOnePasteAndNoDocChange() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val payload = "y".repeat(40)
        WriteCommandAction.runWriteCommandAction(project) { document().insertString(3, payload) }
        assertEquals("expected exactly one paste event", 1, pastes.size)
        assertTrue("no doc.change should be double-logged for a paste", changes.isEmpty())
        assertEquals("hw.py", pastes[0].path)
        assertEquals(40L, pastes[0].length)
        assertEquals(payload, pastes[0].content)
    }
}
