package dev.provenance.recorder.activation

import com.intellij.openapi.components.Service
import dev.provenance.core.Manifest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped activation state: every currently-verified assignment root and its manifest.
 * Consulted by RecordingStatusBarWidgetFactory to decide whether to show the "Provenance:
 * recording" widget, and by RecordingStatusBarWidget to render the assignment count.
 * PRD §4.1 / CLAUDE.md: activation is the privacy gate.
 */
@Service(Service.Level.PROJECT)
class RecorderState {
    private val active = ConcurrentHashMap<Path, Manifest>()

    /** Activated roots whose recording session FAILED to start, with the failure reason.
     * Always a subset of [active]: a degraded root stays activated (so the student still sees
     * an indicator) but is knowingly not recording. */
    private val degraded = ConcurrentHashMap<Path, String>()

    val isActive: Boolean get() = active.isNotEmpty()

    /** Single-assignment convenience: the active manifest when exactly one assignment is
     * recording, else null (including when more than one is active — ambiguous). Multi-root
     * consumers should read [activeManifests] instead. */
    val manifest: Manifest? get() = active.values.singleOrNull()

    val activeManifests: Map<Path, Manifest> get() = active.toMap()

    /** Roots that are activated but not recording, keyed to the reason their session failed
     * to start. Sorted by path so the status bar renders deterministically. */
    val degradedRoots: Map<Path, String> get() = degraded.toSortedMap()

    fun isDegraded(root: Path): Boolean = degraded.containsKey(root.normalize())

    fun activate(root: Path, m: Manifest) {
        val key = root.normalize()
        active[key] = m
        // A fresh activation supersedes any earlier failure for this root; the caller marks it
        // degraded again if starting its session fails this time round.
        degraded.remove(key)
    }

    /**
     * Record that [root] is activated but NOT recording, because starting its session failed.
     * Deliberately not a rollback: the root stays in [active] so the disclosure indicator keeps
     * rendering — a student must be able to tell recording is broken, not merely absent.
     */
    fun markDegraded(root: Path, reason: String) {
        degraded[root.normalize()] = reason
    }

    fun deactivate(root: Path) {
        val key = root.normalize()
        active.remove(key)
        degraded.remove(key)
    }

    fun deactivateAll() {
        active.clear()
        degraded.clear()
    }

    /** Back-compat alias used by existing tearDowns; clears every assignment. */
    fun deactivate() = deactivateAll()
}
