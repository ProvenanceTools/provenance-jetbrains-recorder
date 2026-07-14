package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/**
 * The plugin.xml-instantiated editorActionHandler for "EditorPaste". The platform
 * supplies only the original handler (single-arg constructor, per the editorActionHandler
 * EP contract) — it cannot inject a project-scoped PasteCorrelator, so this factory
 * resolves one per call from RecorderPasteState, and is a no-op passthrough when the
 * project's recorder isn't active (activation is the privacy gate, per CLAUDE.md).
 */
class PasteInterceptHandlerFactory(private val originalHandler: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val correlator = editor.project?.service<RecorderPasteState>()?.correlator
        if (correlator == null) {
            originalHandler.execute(editor, caret, dataContext)
            return
        }
        // Call the public execute() entry (not the protected doExecute): the base
        // EditorActionHandler routes it through our doExecute exactly once, because the
        // wrapper uses the no-arg (runForEachCaret=false) constructor.
        PasteInterceptHandler(originalHandler, correlator).execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        originalHandler.isEnabled(editor, caret, dataContext)
}
