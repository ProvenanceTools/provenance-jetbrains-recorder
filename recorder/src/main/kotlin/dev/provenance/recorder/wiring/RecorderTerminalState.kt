package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.TerminalCommandPayload
import dev.provenance.core.TerminalOpenPayload

/**
 * Project-scoped emit seam for terminal.open / terminal.command, mirroring
 * RecorderPasteState's pattern.
 *
 * References ONLY core payload types (no terminal-plugin types), so it is safe on the
 * main plugin.xml load path — it loads even on IDEs without the Terminal plugin. The
 * gated TerminalWiringStartupActivity (registered only via provjet-terminal.xml, and the
 * only class that references terminal-plugin types) resolves this service and reads the
 * emit callbacks: null until a session activates (privacy gate), nothing recorded while
 * null. The final integration pass sets them on session start and clears on session end.
 */
@Service(Service.Level.PROJECT)
class RecorderTerminalState {
    @Volatile
    var emitTerminalOpen: ((TerminalOpenPayload) -> Unit)? = null

    @Volatile
    var emitTerminalCommand: ((TerminalCommandPayload) -> Unit)? = null
}
