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

    override fun getText(): String {
        val count = project.service<RecorderState>().activeManifests.size
        return if (count > 1) "Provenance: recording ($count assignments)" else "Provenance: recording"
    }

    override fun getTooltipText(): String = "Provenance recorder is active for this assignment."

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    // Note: StatusBarWidget.WidgetPresentation#getClickConsumer returns
    // com.intellij.util.Consumer, not java.util.function.Consumer.
    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    companion object {
        const val WIDGET_ID = "ProvenanceRecordingWidget"
    }
}
