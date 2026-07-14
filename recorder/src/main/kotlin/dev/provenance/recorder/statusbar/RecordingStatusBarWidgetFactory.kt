package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.provenance.recorder.activation.RecorderState

/**
 * Registers the always-visible recording indicator. isAvailable() is the
 * activation gate for the widget itself: PRD §4.1 requires "no UI noise" when
 * the workspace manifest didn't verify, so the widget must not render at all
 * (not render-then-hide) until RecorderState.isActive is true.
 *
 * The platform's newer createWidget(Project, CoroutineScope) delegates to the
 * single-arg createWidget(Project) overridden here, so this covers both call paths.
 */
class RecordingStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = RecordingStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Provenance Recording Indicator"

    override fun isAvailable(project: Project): Boolean =
        project.service<RecorderState>().isActive

    override fun createWidget(project: Project): StatusBarWidget =
        RecordingStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
