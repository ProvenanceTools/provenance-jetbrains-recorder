package dev.provenance.recorder.activation

import com.intellij.openapi.components.Service
import dev.provenance.core.Manifest

/**
 * Project-scoped activation state: whether this workspace's manifest verified,
 * and if so, the manifest itself. Consulted by RecordingStatusBarWidgetFactory
 * (Task 6) to decide whether to show the "Provenance: recording" widget.
 * PRD §4.1 / CLAUDE.md: activation is the privacy gate — everything else in the
 * plugin should eventually consult this before recording anything (Plan 4+).
 */
@Service(Service.Level.PROJECT)
class RecorderState {
    @Volatile
    var manifest: Manifest? = null
        private set

    val isActive: Boolean get() = manifest != null

    fun activate(m: Manifest) {
        manifest = m
    }

    fun deactivate() {
        manifest = null
    }
}
