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

    fun `test widget text shows assignment count when more than one is active`() {
        val state = project.service<RecorderState>()
        state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
        state.activate(java.nio.file.Paths.get("/ws-b"), manifest("hw04"))
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val presentation = widget.getPresentation() as com.intellij.openapi.wm.StatusBarWidget.TextPresentation
        assertEquals("Provenance: recording (2 assignments)", presentation.getText())
    }
}
