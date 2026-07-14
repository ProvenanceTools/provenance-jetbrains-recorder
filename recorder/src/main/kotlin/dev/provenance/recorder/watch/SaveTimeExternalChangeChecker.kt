package dev.provenance.recorder.watch

import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.FsExternalChangePayload

/**
 * Path 1 of PRD §4.5 — the save-time hash check. After the editor writes a watched file,
 * read the just-saved on-disk content and compare it to the expected model; a divergence
 * (format-on-save, or a save racing an external write) emits a modify.
 *
 * RECONCILIATION with Plan 4: Plan 4's doc-save signal is FileDocumentManagerListener
 * .beforeDocumentSaving, which fires BEFORE the physical write — reading disk there would
 * see stale content. The signal that fires exactly when an editor save has updated the
 * file is the VFS VFileContentChangeEvent with isFromSave() == true. So this checker's
 * production trigger is the VfsExternalChangeListener's isFromSave branch (wired by the
 * coordinator), not a beforeDocumentSaving hook. It is kept as a distinct, directly
 * testable unit — [checkAfterSave] can be called from any true post-save hook a later
 * plan adds.
 */
class SaveTimeExternalChangeChecker(
    private val engine: ExternalChangeEngine,
    private val emit: (FsExternalChangePayload) -> Unit,
    private val readDisk: (VirtualFile) -> String = ::readVfsText,
) {
    /** [relativePath] must already be the workspace-relative key (see [relativePathOf]). */
    fun checkAfterSave(relativePath: String, file: VirtualFile) {
        val onDisk = runCatching { readDisk(file) }.getOrNull() ?: return
        engine.onSavedContent(relativePath, onDisk)?.let(emit)
    }
}
