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

    val isActive: Boolean get() = active.isNotEmpty()

    /** Single-assignment convenience: the active manifest when exactly one assignment is
     * recording, else null (including when more than one is active — ambiguous). Multi-root
     * consumers should read [activeManifests] instead. */
    val manifest: Manifest? get() = active.values.singleOrNull()

    val activeManifests: Map<Path, Manifest> get() = active.toMap()

    fun activate(root: Path, m: Manifest) {
        active[root.normalize()] = m
    }

    fun deactivate(root: Path) {
        active.remove(root.normalize())
    }

    fun deactivateAll() {
        active.clear()
    }

    /** Back-compat alias used by existing tearDowns; clears every assignment. */
    fun deactivate() = deactivateAll()
}
