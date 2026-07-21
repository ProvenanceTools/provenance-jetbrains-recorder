package dev.provenance.recorder.wiring.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.view.TerminalView
import dev.provenance.core.TerminalCommandPayload
import dev.provenance.core.TerminalOpenPayload
import dev.provenance.recorder.wiring.RecorderTerminalState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * terminal.open / terminal.command wiring. Registered ONLY via provjet-terminal.xml's
 * optional <depends> on org.jetbrains.plugins.terminal. This class references
 * terminal-plugin types directly (com.intellij.terminal.frontend.* and
 * org.jetbrains.plugins.terminal.view.*), so it must never be reachable from the main
 * plugin.xml — a static reference to an absent plugin's type throws NoClassDefFoundError
 * at class-verify time regardless of any runtime guard. If the Terminal plugin is absent,
 * provjet-terminal.xml is never loaded, this class is never classloaded, and no
 * terminal.* events are emitted.
 *
 * EXPERIMENTAL-API RISK: every terminal type used here is @ApiStatus.Experimental in the
 * platform (the "Reworked Terminal" frontend/backend rewrite). Confirmed present and
 * matching these signatures at the target platform (IntelliJ 2026.1.4, build 261), but
 * JetBrains may break it across versions without the usual deprecation cycle. If it moves,
 * the documented fallback is the legacy TerminalView/ShellTerminalWidget singleton API.
 *
 * Mirrors packages/recorder/src/wiring/terminal-wiring.ts: assign a stable counter-based
 * terminal_id per terminal view, emit terminal.open once shell-integration status is known
 * (best effort — many shells never resolve integration, matching VS Code's "record the
 * gap, don't fail" rule, PRD §4.4), then emit terminal.command on each finished command
 * when shell integration is available. The emit seam is [RecorderTerminalState] (core-only
 * types, main-path safe), null until a session activates.
 */
class TerminalWiringStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val state = project.service<RecorderTerminalState>()
        val counter = AtomicInteger(0)
        // Project-scoped connection; the terminal listeners live for the project's
        // lifetime (like the paste interceptor). The privacy gate is the null emit seam,
        // not subscription lifetime.
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            TerminalTabsManagerListener.TOPIC,
            object : TerminalTabsManagerListener {
                override fun terminalViewCreated(view: TerminalView) {
                    val terminalId = "term-${counter.getAndIncrement()}"
                    // Structured concurrency: the view owns the scope, so this coroutine
                    // is cancelled when the view is disposed — no manual Job to leak.
                    view.coroutineScope.launch {
                        val shellIntegration =
                            withTimeoutOrNull(SHELL_INTEGRATION_TIMEOUT_MS) { view.shellIntegrationDeferred.await() }
                        val shell = shellNameOf(view)
                        // Terminal cwd resolution, VERIFIED against the real platform jar (IntelliJ
                        // 2026.1.4, build 261; org.jetbrains.plugins.terminal:terminal.jar,
                        // intellij.terminal.frontend.jar): `startupOptionsDeferred` resolves to
                        // org.jetbrains.plugins.terminal.session.TerminalStartupOptions (NOT
                        // ShellStartupOptions — that's a distinct, unrelated builder-pattern class in
                        // the same plugin), which exposes both `shellCommand` (used above) and a
                        // `@NotNull val workingDirectory: String`. It is guaranteed non-null by the
                        // platform's own nullability annotation, so no safe-call/elvis is needed on it;
                        // runCatching still guards Paths.get() (malformed path text) and the await()
                        // itself, falling back to cwd = null (never inventing a directory) — which
                        // routes to "no owner" per this plan's locked design.
                        val cwd: java.nio.file.Path? = runCatching {
                            java.nio.file.Paths.get(view.startupOptionsDeferred.await().workingDirectory)
                        }.getOrNull()

                        state.emitTerminalOpen?.invoke(
                            cwd,
                            TerminalOpenPayload(
                                terminalId = terminalId,
                                shell = shell,
                                shellIntegration = shellIntegration != null,
                            ),
                        )

                        shellIntegration?.addCommandExecutionListener(
                            connection,
                            object : TerminalCommandExecutionListener {
                                override fun commandFinished(event: TerminalCommandFinishedEvent) {
                                    val block = event.commandBlock
                                    state.emitTerminalCommand?.invoke(
                                        cwd,
                                        TerminalCommandPayload(
                                            terminalId = terminalId,
                                            command = block.executedCommand ?: "",
                                            exitCode = block.exitCode,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    /**
     * Best-effort shell name: the first token of the resolved startup command
     * (mirrors VS Code's `creationOptions?.shellPath ?? 'unknown'`). startupOptionsDeferred
     * may never resolve for some launch modes, so it is time-boxed with a fallback.
     */
    private suspend fun shellNameOf(view: TerminalView): String =
        withTimeoutOrNull(STARTUP_OPTIONS_TIMEOUT_MS) {
            view.startupOptionsDeferred.await().shellCommand.firstOrNull()
        } ?: "unknown"

    companion object {
        /** How long to wait for shell integration before emitting shell_integration=false. */
        const val SHELL_INTEGRATION_TIMEOUT_MS = 3_000L

        /** How long to wait for the resolved shell command before falling back to "unknown". */
        const val STARTUP_OPTIONS_TIMEOUT_MS = 1_000L
    }
}
