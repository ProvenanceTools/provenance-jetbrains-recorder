package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * The plugin.xml-instantiated editorActionHandler for "EditorPaste". The platform
 * supplies only the original handler (single-arg constructor, per the editorActionHandler
 * EP contract) — it cannot inject a project-scoped PasteCorrelator, so this factory
 * resolves one per call from RecorderPasteState, routed by the PATH of the file being
 * pasted into (the nearest-enclosing session's correlator, so concurrent sessions don't
 * clobber one another). It is a no-op passthrough when no session owns the path (activation
 * is the privacy gate, per CLAUDE.md).
 */
class PasteInterceptHandlerFactory(private val originalHandler: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val path = vf?.let { runCatching { it.toNioPath() }.getOrNull() }
        val correlator = editor.project?.service<RecorderPasteState>()?.resolveCorrelator?.invoke(path)
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
