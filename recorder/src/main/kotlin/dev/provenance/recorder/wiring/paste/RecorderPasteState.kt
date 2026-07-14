package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.components.Service
import dev.provenance.recorder.paste.PasteCorrelator

/**
 * Project-scoped holder for the active session's PasteCorrelator, mirroring
 * dev.provenance.recorder.activation.RecorderState's pattern. The plugin.xml-registered
 * PasteInterceptHandlerFactory (global, one per editor) resolves the correlator from
 * here per keystroke: `correlator` stays null until a session activates (privacy gate),
 * and while null the factory is a pure passthrough — paste keeps working, nothing is
 * recorded. RecordingSessionController sets it on start and clears it on session end.
 */
@Service(Service.Level.PROJECT)
class RecorderPasteState {
    @Volatile
    var correlator: PasteCorrelator? = null
}
