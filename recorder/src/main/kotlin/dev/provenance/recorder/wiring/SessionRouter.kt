package dev.provenance.recorder.wiring

import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path

/**
 * What a single owning recording session exposes to the project-scoped DocWiring/
 * SelectionWiring routers. Implemented by RecordingSessionController (session package):
 * this interface lives in `wiring` (not `session`) so DocWiring/SelectionWiring — themselves
 * in `wiring` — don't need to depend on the `session` package.
 */
interface RecordableSessionSink {
    val workspaceRoot: Path
    val pasteCorrelator: PasteCorrelator?
    fun onDocOpen(payload: DocOpenPayload)
    fun onDocChange(payload: DocChangePayload)
    /**
     * A watched file's editor save has COMPLETED and [onDiskContent] is what landed on disk.
     *
     * Deliberately not `onDocSave(payload)`: the hash a doc.save carries must be of the bytes
     * that reached disk, and only the caller of this method is in a position to know them.
     * IntelliJ mutates the document during the save (its TrailingSpacesStripper and the rest
     * of Actions-on-Save all run from `beforeDocumentSaving`), so the buffer text at save
     * entry is not what gets written — hashing it records a state that never existed on disk
     * and makes the analyzer's "submitted file matches the last recorded on-disk state" check
     * fail against an honest student.
     *
     * Passing content rather than a payload also keeps the ORDER right. The implementor emits
     * fs.external_change (if the write diverged from the expected-content model) and *then*
     * doc.save carrying this exact content's hash — the pairing `reconstruct-file.ts`'s
     * save-path signature matches on. Two sink calls could not guarantee that pairing, and a
     * second disk read could not guarantee the two events describe the same bytes.
     */
    fun onSaveObserved(relativePath: String, onDiskContent: String)
    fun onDocClose(payload: DocClosePayload)
    fun onPaste(payload: PastePayload)
    fun onSelectionChange(payload: SelectionChangePayload)
}

/**
 * Resolves the one session (if any) that owns a given file path, by nearest-ancestor verified
 * manifest root. Implemented by RecorderSessionManager against its live session registry.
 * Returning null is the router's privacy gate: no owner ⇒ nothing is recorded for that path,
 * mirroring RecorderTerminalState/RecorderGitState's null-callback gate.
 */
fun interface SessionRouter {
    fun sinkFor(nioPath: Path): RecordableSessionSink?
}
