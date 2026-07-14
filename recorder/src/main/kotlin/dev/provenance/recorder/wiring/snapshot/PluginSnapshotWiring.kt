package dev.provenance.recorder.wiring.snapshot

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import dev.provenance.core.ExtSnapshotEntry
import dev.provenance.core.ExtSnapshotPayload
import dev.provenance.recorder.io.FlushScheduler
import java.util.concurrent.ScheduledFuture

/**
 * Periodic ext.snapshot emitter (recorder PRD §4.4). Mirrors the VS Code
 * extension-snapshot.ts: emit one snapshot immediately at session start, then re-emit
 * every [intervalMs] (default 5 min).
 *
 * This is the always-on signal — [PluginManagerCore] is core-platform
 * (com.intellij.modules.platform), always present — so unlike terminal/git wiring it
 * needs no optional-dependency gating and lives on the main plugin.xml load path.
 *
 * Shape mirrors Heartbeat/PasteAnomalyTicker exactly: the plugin enumeration is injected
 * ([getPlugins]) and the interval scheduling goes through an injected [FlushScheduler],
 * so tick/immediate-emit/dispose are deterministic plain unit tests with no live IDE.
 * The [fromPlatform] factory isolates the one line that touches PluginManagerCore.
 * No background task without a dispose path (CLAUDE.md): [dispose] cancels the schedule
 * and is idempotent.
 *
 * Not wired into activation or the controller here — a later integration pass constructs
 * this with the session's emit seam (consistent with the standalone-wiring pattern).
 */
class PluginSnapshotWiring(
    private val emit: (ExtSnapshotPayload) -> Unit,
    private val getPlugins: () -> List<ExtSnapshotEntry>,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
    scheduler: FlushScheduler,
) : Disposable {
    @Volatile
    private var disposed = false

    // Emit immediately (session-start snapshot), then schedule the periodic re-emit.
    private val future: ScheduledFuture<*>

    init {
        tick()
        future = scheduler.scheduleAtFixedRate(intervalMs) { if (!disposed) tick() }
    }

    /** One snapshot: enumerate plugins → emit ext.snapshot. */
    fun tick() {
        emit(ExtSnapshotPayload(getPlugins()))
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        future.cancel(false)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 5 * 60 * 1000L

        /**
         * Production factory: enumerate installed plugins from PluginManagerCore. Kept
         * separate from the testable core so the class itself never has to call the
         * platform. Only enabled plugins carry a real [com.intellij.openapi.extensions.PluginId];
         * `version`/`isEnabled` come straight off the descriptor.
         */
        fun fromPlatform(
            emit: (ExtSnapshotPayload) -> Unit,
            scheduler: FlushScheduler,
            intervalMs: Long = DEFAULT_INTERVAL_MS,
        ): PluginSnapshotWiring =
            PluginSnapshotWiring(
                emit = emit,
                getPlugins = {
                    PluginManagerCore.plugins.map { descriptor ->
                        ExtSnapshotEntry(
                            id = descriptor.pluginId.idString,
                            version = descriptor.version ?: "unknown",
                            enabled = descriptor.isEnabled,
                        )
                    }
                },
                intervalMs = intervalMs,
                scheduler = scheduler,
            )
    }
}
