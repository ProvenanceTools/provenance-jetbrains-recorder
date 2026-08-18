package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Manifest
import dev.provenance.recorder.activation.RecorderState

class RecordingStatusBarWidgetFactoryTest : BasePlatformTestCase() {

    // RecorderState is a project service shared across the light-fixture methods;
    // reset it after each test so activation from one test doesn't leak into another.
    override fun tearDown() {
        try {
            project.service<RecorderState>().deactivate()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(assignment: String = "hw03") =
        Manifest(assignment, "fa26", "2026-09-15T00:00:00Z", listOf("$assignment.py"), "a".repeat(128))

    fun `test widget is not available before activation`() {
        val factory = RecordingStatusBarWidgetFactory()
        assertFalse(factory.isAvailable(project))
    }

    fun `test widget is available after activation`() {
        project.service<RecorderState>().activate(java.nio.file.Paths.get("/ws"), manifest())
        val factory = RecordingStatusBarWidgetFactory()
        assertTrue(factory.isAvailable(project))
    }

    fun `test widget text matches the PRD-specified indicator string`() {
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val presentation = widget.getPresentation() as StatusBarWidget.TextPresentation
        assertEquals("Provenance: recording", presentation.getText())
    }

    fun `test factory id matches widget ID`() {
        val factory = RecordingStatusBarWidgetFactory()
        val widget = factory.createWidget(project)
        assertEquals(factory.getId(), widget.ID())
    }

    fun `test widget text is unchanged when exactly one assignment is active`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws"), manifest())
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val presentation = widget.getPresentation() as StatusBarWidget.TextPresentation
        assertEquals("Provenance: recording", presentation.getText())
    }

    fun `test widget text shows assignment count when more than one is active`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
        state.activate(java.nio.file.Paths.get("/ws-b"), manifest("hw04"))
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val presentation = widget.getPresentation() as com.intellij.openapi.wm.StatusBarWidget.TextPresentation
        assertEquals("Provenance: recording (2 assignments)", presentation.getText())
    }

    private fun text(): String =
        (RecordingStatusBarWidgetFactory().createWidget(project).getPresentation() as StatusBarWidget.TextPresentation)
            .getText()

    // Degraded rendering: an activated-but-not-recording root must read as BROKEN, not as
    // recording and not as absent — the widget is the student's only signal that the session
    // failed to start.
    fun `test widget text reports the sole assignment as not recording when it is degraded`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws"), manifest())
        state.markDegraded(java.nio.file.Paths.get("/ws"), "NotImplementedError: an operation is not implemented")
        assertEquals("Provenance: not recording (error)", text())
    }

    fun `test widget text reports the error count when every assignment is degraded`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
        state.activate(java.nio.file.Paths.get("/ws-b"), manifest("hw04"))
        state.markDegraded(java.nio.file.Paths.get("/ws-a"), "boom")
        state.markDegraded(java.nio.file.Paths.get("/ws-b"), "boom")
        assertEquals("Provenance: not recording (2 errors)", text())
    }

    fun `test widget text conveys both sides when some assignments record and some are degraded`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
        state.activate(java.nio.file.Paths.get("/ws-b"), manifest("hw04"))
        state.activate(java.nio.file.Paths.get("/ws-c"), manifest("hw05"))
        state.markDegraded(java.nio.file.Paths.get("/ws-c"), "boom")
        assertEquals("Provenance: recording (2 of 3 assignments, 1 error)", text())
    }

    fun `test widget tooltip names the degraded assignment and its reason`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
        state.markDegraded(java.nio.file.Paths.get("/ws-a"), "NotImplementedError: an operation is not implemented")
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val tooltip = (widget.getPresentation() as StatusBarWidget.TextPresentation).getTooltipText().orEmpty()
        assertTrue("tooltip must name the failing root, got: $tooltip", tooltip.contains("/ws-a"))
        assertTrue("tooltip must carry the reason, got: $tooltip", tooltip.contains("an operation is not implemented"))
    }

    fun `test widget tooltip is unchanged while everything records`() {
        project.service<RecorderState>().activate(java.nio.file.Paths.get("/ws"), manifest())
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val tooltip = (widget.getPresentation() as StatusBarWidget.TextPresentation).getTooltipText()
        assertEquals("Provenance recorder is active for this assignment.", tooltip)
    }
}
