package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.components.Service
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path

/**
 * Project-scoped routing seam for signal 2 (the EditorPaste action wrapper), mirroring
 * [dev.provenance.recorder.wiring.RecorderTerminalState]/[dev.provenance.recorder.wiring.RecorderGitState].
 * The plugin.xml-registered PasteInterceptHandlerFactory is global (one per editor) and cannot
 * be injected with a session's PasteCorrelator, so it resolves one here, per keystroke, by the
 * PATH of the file being pasted into.
 *
 * This is a resolver, NOT a single slot, on purpose: with N concurrent sessions (nested-manifest
 * discovery) a single project-scoped slot would be clobbered — the last session to start would
 * win the slot and every session's paste would feed that one session's correlator, and the last
 * session to stop would null the slot out from under the others. Routing by path sends each
 * paste to the nearest-enclosing session's own correlator (via
 * RecorderSessionManager.sinkFor → RecordableSessionSink.pasteCorrelator), exactly as the
 * doc/selection/terminal/git firehoses are routed.
 *
 * [resolveCorrelator] stays null until at least one session is active (privacy gate); while null
 * the factory is a pure passthrough — paste keeps working, nothing is recorded. A resolved path
 * with no owning session likewise yields null (same gate). RecorderSessionManager installs the
 * resolver for as long as at least one session is live and clears it back to null when the last
 * session stops.
 */
@Service(Service.Level.PROJECT)
class RecorderPasteState {
    @Volatile
    var resolveCorrelator: ((path: Path?) -> PasteCorrelator?)? = null
}
