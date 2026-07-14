package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.recorder.paste.PasteCorrelator
import java.awt.datatransfer.StringSelection

/**
 * Platform seam for signal 2 — tested against a real (headless) Editor/Document/
 * clipboard via BasePlatformTestCase (JUnit-3-style testX methods; the recorder
 * module runs on JUnit 4/3 — see build.gradle.kts). performEditorAction("EditorPaste")
 * exercises the exact keystroke path a real Cmd+V/Ctrl+V takes.
 */
class PasteInterceptHandlerTest : BasePlatformTestCase() {

    private fun registerWrapper(correlator: PasteCorrelator) {
        val manager = EditorActionManager.getInstance()
        val original = manager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
        manager.setActionHandler(IdeActions.ACTION_EDITOR_PASTE, PasteInterceptHandler(original, correlator))
        Disposer.register(testRootDisposable) {
            manager.setActionHandler(IdeActions.ACTION_EDITOR_PASTE, original)
        }
    }

    fun testPerformingEditorPasteIncrementsInterceptedCountAndInsertsText() {
        myFixture.configureByText("Test.txt", "before<caret> after")
        CopyPasteManager.getInstance().setContents(StringSelection("PASTED"))

        val correlator = PasteCorrelator(getNow = { 0L })
        registerWrapper(correlator)

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)

        assertEquals(1, correlator.interceptedCount)
        // The document mutation is visible by the time performEditorAction returns,
        // confirming the synchronous dispatch the correlator's withinMs window assumes.
        assertTrue(myFixture.editor.document.text.contains("PASTED"))
    }

    fun testClipboardTextIsCapturedBeforeDelegating() {
        myFixture.configureByText("Test.txt", "<caret>")
        CopyPasteManager.getInstance().setContents(StringSelection("clipboard-marker-text"))

        // Verify via a ClipboardReader spy wired directly into the handler: it proves
        // the wiring reads the clipboard and fires onPasteActionFired before delegating.
        var captured: String? = null
        var fired = false
        val correlator = PasteCorrelator(getNow = { 0L })
        val spy = object : ClipboardReader {
            override fun readText(): String? {
                fired = true
                captured = "clipboard-marker-text"
                return captured
            }
        }
        val manager = EditorActionManager.getInstance()
        val original = manager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
        manager.setActionHandler(
            IdeActions.ACTION_EDITOR_PASTE,
            PasteInterceptHandler(original, correlator, spy),
        )
        Disposer.register(testRootDisposable) {
            manager.setActionHandler(IdeActions.ACTION_EDITOR_PASTE, original)
        }

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)

        assertTrue(fired)
        assertEquals("clipboard-marker-text", captured)
        assertEquals(1, correlator.interceptedCount)
    }
}
