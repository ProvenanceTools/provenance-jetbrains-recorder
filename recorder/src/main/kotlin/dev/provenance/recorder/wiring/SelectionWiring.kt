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
 * selection.change wiring (recorder PRD §4.2). Mirrors the VS Code recorder's
 * onDidChangeTextEditorSelection subscription (doc-wiring.ts).
 *
 * VS Code fires one selection event for both cursor moves and range selections. IntelliJ splits
 * this into a [CaretListener] (caret position moved) and a [SelectionListener] (selection extent
 * changed), both registered on the global [EditorFactory] event multicaster — the same seam
 * DocWiring uses. Either fire recomputes the payload from the editor's *primary caret* (matching
 * doc-events.ts, which uses the first selection), so a bare click emits was_selection=false and a
 * drag/extend emits was_selection=true.
 *
 * Scoping is identical to DocWiring: only files that pass [isRecordablePath] (inside the workspace,
 * on the local FS, not under `.provenance/`, not an activation manifest) are recorded — a student's
 * caret movement in the live log or a scratch file is never emitted. A per-editor last-state dedup
 * suppresses the redundant second event when a single gesture fires both listeners with the same
 * resulting selection.
 *
 * [localFsOf]/[nioPathOf] are injectable so the transform is testable under a light fixture whose
 * files are not on the local FS (production uses the real VirtualFile checks).
 */
class SelectionWiring(
    private val provenanceDir: Path,
    private val workspaceRoot: Path,
    private val emitSelectionChange: (SelectionChangePayload) -> Unit,
    parentDisposable: Disposable,
    private val manifestNames: Set<String> = MANIFEST_FILE_NAMES_FILTER,
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

    /** Recompute the selection.change payload from the editor's primary caret and emit (deduped). */
    private fun handle(editor: Editor) {
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!isRecordable(vf)) return

        val caret = editor.caretModel.primaryCaret
        val wasSelection = caret.hasSelection()
        val startPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionStart) else caret.logicalPosition
        val endPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionEnd) else caret.logicalPosition

        val payload = buildSelectionChangePayload(
            path = relativePath(vf),
            startLine = startPos.line.toLong(),
            startChar = startPos.column.toLong(),
            endLine = endPos.line.toLong(),
            endChar = endPos.column.toLong(),
            wasSelection = wasSelection,
        )

        // Suppress the redundant second event when one gesture triggers both listeners.
        if (lastEmitted[editor] == payload) return
        lastEmitted[editor] = payload
        emitSelectionChange(payload)
    }

    private fun isRecordable(vf: VirtualFile): Boolean =
        isRecordablePath(nioPathOf(vf), localFsOf(vf), workspaceRoot, provenanceDir, manifestNames)

    private fun relativePath(vf: VirtualFile): String {
        val nio = nioPathOf(vf) ?: return vf.name
        return runCatching { workspaceRoot.normalize().relativize(nio.normalize()).toString().replace('\\', '/') }
            .getOrDefault(vf.name)
    }
}
