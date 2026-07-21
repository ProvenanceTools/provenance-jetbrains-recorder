package dev.provenance.recorder.wiring

import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
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
    fun onDocSave(payload: DocSavePayload)
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
