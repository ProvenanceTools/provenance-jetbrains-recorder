package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.events.buildSelectionChangePayload
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * selection.change wiring (recorder PRD §4.2). Registered ONCE, project-scoped, mirroring
 * DocWiring's router-based rewrite for the same reason: a per-session listener filtered only
 * by "is this under my root" would double-fire for overlapping/nested assignment roots.
 */
class SelectionWiring(
    private val router: SessionRouter,
    parentDisposable: Disposable,
    private val localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    private val nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
) {
    private val lastEmitted = WeakHashMap<Editor, SelectionChangePayload>()

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = handle(event.editor)
            },
            parentDisposable,
        )
        multicaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) = handle(event.editor)
            },
            parentDisposable,
        )
    }

    private fun handle(editor: Editor) {
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!localFsOf(vf)) return
        val path = nioPathOf(vf) ?: return
        val sink = router.sinkFor(path) ?: return

        val caret = editor.caretModel.primaryCaret
        val wasSelection = caret.hasSelection()
        val startPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionStart) else caret.logicalPosition
        val endPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionEnd) else caret.logicalPosition

        val relPath = runCatching {
            sink.workspaceRoot.normalize().relativize(path.normalize()).toString().replace('\\', '/')
        }.getOrDefault(vf.name)

        val payload = buildSelectionChangePayload(
            path = relPath,
            startLine = startPos.line.toLong(),
            startChar = startPos.column.toLong(),
            endLine = endPos.line.toLong(),
            endChar = endPos.column.toLong(),
            wasSelection = wasSelection,
        )

        if (lastEmitted[editor] == payload) return
        lastEmitted[editor] = payload
        sink.onSelectionChange(payload)
    }
}
