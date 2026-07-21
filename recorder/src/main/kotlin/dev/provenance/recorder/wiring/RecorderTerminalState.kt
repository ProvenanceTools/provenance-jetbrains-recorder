package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.TerminalCommandPayload
import dev.provenance.core.TerminalOpenPayload
import java.nio.file.Path

/**
 * Project-scoped emit seam for terminal.open / terminal.command. [cwd] is the terminal's
 * working directory (when resolvable), used by the installed router to find the owning
 * session by nearest-ancestor; null routes to no owner (dropped, never guessed).
 *
 * References ONLY core payload types (no terminal-plugin types), so it is safe on the main
 * plugin.xml load path. The gated TerminalWiringStartupActivity (registered only via
 * provjet-terminal.xml) resolves this service and reads the emit callbacks: null until at
 * least one session is active (privacy gate), nothing recorded while null. RecorderSessionManager
 * installs the router once, for as long as at least one session is active, and clears it back to
 * null when the last session stops.
 */
@Service(Service.Level.PROJECT)
class RecorderTerminalState {
    @Volatile
    var emitTerminalOpen: ((cwd: Path?, TerminalOpenPayload) -> Unit)? = null

    @Volatile
    var emitTerminalCommand: ((cwd: Path?, TerminalCommandPayload) -> Unit)? = null
}
