package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.SelectionChangePayload
import java.nio.file.Path
import java.nio.file.Paths

class SelectionWiringTest : BasePlatformTestCase() {
    private val selections = mutableListOf<SelectionChangePayload>()

    private val workspaceRoot: Path = Paths.get("/ws")
    private val provenanceDir: Path = Paths.get("/ws/.provenance")

    override fun tearDown() {
        try {
            selections.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun install(
        localFsOf: (com.intellij.openapi.vfs.VirtualFile) -> Boolean = { true },
    ) = SelectionWiring(
        provenanceDir = provenanceDir,
        workspaceRoot = workspaceRoot,
        emitSelectionChange = { selections.add(it) },
        parentDisposable = testRootDisposable,
        localFsOf = localFsOf,
        nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
    )

    fun testCursorMoveEmitsSelectionChangeWasSelectionFalse() {
        myFixture.configureByText("hw.py", "print(1)\nprint(2)\n")
        install()
        ApplicationManager.getApplication().runWriteAction {
            myFixture.editor.caretModel.moveToOffset(3)
        }
        assertTrue("a cursor move must emit selection.change", selections.isNotEmpty())
        val last = selections.last()
        assertEquals("hw.py", last.path)
        assertFalse("bare cursor move is not a selection", last.wasSelection)
        assertEquals(last.range.start, last.range.end)
    }

    fun testSelectingTextEmitsWasSelectionTrueWithExtent() {
        myFixture.configureByText("hw.py", "print(1)\nprint(2)\n")
        install()
        selections.clear()
        ApplicationManager.getApplication().runWriteAction {
            myFixture.editor.selectionModel.setSelection(0, 5)
        }
        assertTrue("selecting text must emit selection.change", selections.isNotEmpty())
        val sel = selections.last { it.wasSelection }
        assertEquals("hw.py", sel.path)
        assertTrue(sel.wasSelection)
        assertEquals(0L, sel.range.start.line)
        assertEquals(0L, sel.range.start.character)
        assertEquals(0L, sel.range.end.line)
        assertEquals(5L, sel.range.end.character)
    }

    fun testNonRecordableFileEmitsNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install(localFsOf = { false })
        selections.clear()
        ApplicationManager.getApplication().runWriteAction {
            myFixture.editor.caretModel.moveToOffset(3)
            myFixture.editor.selectionModel.setSelection(0, 4)
        }
        assertTrue("non-recordable file must not emit selection.change", selections.isEmpty())
    }
}
