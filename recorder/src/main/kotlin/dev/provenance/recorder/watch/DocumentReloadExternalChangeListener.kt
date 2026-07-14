package dev.provenance.recorder.watch

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.FsExternalChangePayload
import java.nio.file.Path

/**
 * Path 3 of PRD §4.5 — reload-from-disk detection. IntelliJ fires
 * fileContentReloaded(file, document) exactly and only when it has silently reloaded a
 * clean editor buffer from disk (an external write landed while the buffer was clean).
 *
 * This is where the port is MORE precise than VS Code: VS Code has to INFER a silent
 * reload from an absent change `reason` plus a clean-buffer flag; IntelliJ tells us
 * directly, so there is no heuristic and no would-be-doc.change to suppress.
 *
 * VERIFY AT EXECUTION: confirm that a real silent reload delivers fileContentReloaded and
 * does NOT also drive DocumentListener.documentChanged (which would double-handle with
 * the expected-model feeder). See VfsReloadDocumentChangeInteractionTest.
 */
class DocumentReloadExternalChangeListener(
    private val workspaceRoot: Path,
    private val engine: ExternalChangeEngine,
    private val emit: (FsExternalChangePayload) -> Unit,
) : FileDocumentManagerListener {
    override fun fileContentReloaded(file: VirtualFile, document: Document) {
        val rel = relativePathOf(file, workspaceRoot) ?: return
        engine.onReload(rel, document.text)?.let(emit)
    }
}
