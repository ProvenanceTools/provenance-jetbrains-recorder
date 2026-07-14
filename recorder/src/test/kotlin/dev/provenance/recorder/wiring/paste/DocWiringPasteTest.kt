package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.PastePayload
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.wiring.DocWiring
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Integration: paste classification folded INTO Plan 4's DocWiring listener (Plan 6
 * reconciliation). Proves the correlator-wired DocWiring emits exactly ONE event per
 * change — a `paste` for a paste-shaped bulk insert, a `doc.change` for typing — with
 * no double-logging against a second listener (the double-count risk the plan flagged).
 */
class DocWiringPasteTest : BasePlatformTestCase() {
    private val changes = mutableListOf<DocChangePayload>()
    private val pastes = mutableListOf<PastePayload>()
    private val workspaceRoot: Path = Paths.get("/ws")
    private var now = 0L
    private lateinit var correlator: PasteCorrelator

    private fun install() {
        correlator = PasteCorrelator(getNow = { now })
        DocWiring(
            project = project,
            provenanceDir = Paths.get("/ws/.provenance"),
            workspaceRoot = workspaceRoot,
            emitDocOpen = {},
            emitDocChange = { changes.add(it) },
            emitDocSave = {},
            emitDocClose = {},
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
            emitPaste = { pastes.add(it) },
            pasteCorrelator = correlator,
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
