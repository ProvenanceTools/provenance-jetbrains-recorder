package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import dev.provenance.recorder.activation.RecorderState
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Non-dismissible status bar item indicating that recording is active.
 * PRD §4.1: "shows a non-dismissible status bar item ('Provenance: recording')
 * so the student is always aware that telemetry is active."
 * Mirrors packages/recorder/src/activation/status-bar.ts.
 */
class RecordingStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // Nothing to wire up beyond text; no click/hover state to install.
    }

    override fun dispose() {
        // No owned resources yet. Kept as an explicit hook (CLAUDE.md: every
        // listener/timer/watcher has a dispose() path) for when Plan 4+ wires
        // this widget to live session state.
    }

    /**
     * Extends the existing assignment-count rendering with the degraded case: an assignment that
     * is activated but whose session failed to start must read as BROKEN, not as recording and
     * not as absent — the widget is the student's only signal that recording died. Mixed
     * projects report both halves rather than hiding either.
     */
    override fun getText(): String {
        val state = project.service<RecorderState>()
        val total = state.activeManifests.size
        val degraded = state.degradedRoots.size
        val recording = total - degraded
        return when {
            degraded == 0 -> if (total > 1) "Provenance: recording ($total assignments)" else "Provenance: recording"
            recording == 0 -> if (degraded > 1) "Provenance: not recording ($degraded errors)" else "Provenance: not recording (error)"
            else -> "Provenance: recording ($recording of $total assignments, ${errors(degraded)})"
        }
    }

    override fun getTooltipText(): String {
        val degraded = project.service<RecorderState>().degradedRoots
        if (degraded.isEmpty()) return "Provenance recorder is active for this assignment."
        val what = if (degraded.size == 1) "1 assignment" else "${degraded.size} assignments"
        return "Provenance is NOT recording for $what: " +
            degraded.entries.joinToString("; ") { (root, reason) -> "$root ($reason)" }
    }

    private fun errors(count: Int): String = if (count == 1) "1 error" else "$count errors"

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    // Note: StatusBarWidget.WidgetPresentation#getClickConsumer returns
    // com.intellij.util.Consumer, not java.util.function.Consumer.
    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    companion object {
        const val WIDGET_ID = "ProvenanceRecordingWidget"
    }
}
