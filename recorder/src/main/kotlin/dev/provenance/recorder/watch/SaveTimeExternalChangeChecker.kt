package dev.provenance.recorder.watch

import dev.provenance.core.FsExternalChangePayload

/**
 * Path 1 of PRD §4.5 — the save-time hash check. After the editor writes a watched file,
 * read the just-saved on-disk content and compare it to the expected model; a divergence
 * (format-on-save, or a save racing an external write) emits a modify.
 *
 * RECONCILIATION with the doc.save path: FileDocumentManagerListener.beforeDocumentSaving
 * fires BEFORE the physical write — reading disk there would see stale content, and hashing
 * the buffer there records content that never reached disk (IntelliJ mutates the document
 * from inside that callback). The signal that fires exactly when an editor save has updated
 * the file is the VFS VFileContentChangeEvent with isFromSave() == true.
 *
 * That signal is now owned by DocWiring's post-save listener, which routes it to the owning
 * session and calls [checkSavedContent] via ExternalChangeCoordinator BEFORE recording the
 * doc.save for the same bytes. This checker stays a distinct, directly testable unit; it just
 * takes content instead of reading it, so caller and checker cannot disagree about what was
 * written.
 */
class SaveTimeExternalChangeChecker(
    private val engine: ExternalChangeEngine,
    private val emit: (FsExternalChangePayload) -> Unit,
) {
    /**
     * [relativePath] must already be the workspace-relative key (see [relativePathOf]), and
     * [onDiskContent] what the editor just wrote there.
     *
     * The content is passed in rather than read here so that this comparison and the doc.save
     * the caller records next describe the same bytes — see
     * [dev.provenance.recorder.wiring.RecordableSessionSink.onSaveObserved].
     */
    fun checkSavedContent(relativePath: String, onDiskContent: String) {
        engine.onSavedContent(relativePath, onDiskContent)?.let(emit)
    }
}
