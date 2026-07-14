package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import dev.provenance.recorder.paste.PasteCorrelator

/**
 * Signal 2 of three-signal paste detection, wrapping IdeActions.ACTION_EDITOR_PASTE
 * ("EditorPaste" — the action bound to Cmd+V/Ctrl+V inside a text editor; NOT the
 * generic "$Paste"). Mirrors the wrapping pattern IntelliJ's own PasteHandler uses:
 * hold the original handler, do our own work, then delegate.
 *
 * Reads the clipboard and records the pending expectation BEFORE delegating — the
 * original handler is what mutates the Document (synchronously), and any
 * CopyPastePostProcessor/reformat-on-paste rewriting happens inside that call, so
 * this is the last point we can see the pre-reformat clipboard text.
 *
 * KNOWN GAP (parity with VS Code's documented blind spot): a tool that mutates the
 * Document directly via the PSI/Document API bypasses the action system and won't
 * fire this handler. PasteCorrelator's classifier (signal 1) still sees the large
 * insert — it is just unconfirmed ("paste_likely", not "paste_confirmed").
 */
class PasteInterceptHandler(
    private val originalHandler: EditorActionHandler,
    private val correlator: PasteCorrelator,
    private val clipboard: ClipboardReader = CopyPasteManagerClipboardReader(),
) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        correlator.onPasteActionFired(clipboard.readText())
        originalHandler.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        originalHandler.isEnabled(editor, caret, dataContext)
}
