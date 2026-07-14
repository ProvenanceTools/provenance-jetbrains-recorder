package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Manifest
import kotlinx.coroutines.runBlocking

class RecorderStateTest : BasePlatformTestCase() {

    // The light-fixture project (and its RecorderState service) is shared across
    // test methods, so reset activation after each test to keep them isolated.
    override fun tearDown() {
        try {
            project.service<RecorderState>().deactivate()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(assignmentId: String = "hw03") =
        Manifest(assignmentId, "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))

    fun `test isActive is false by default`() {
        assertFalse(project.service<RecorderState>().isActive)
    }

    fun `test activate then isActive is true, manifest is stored`() {
        val state = project.service<RecorderState>()
        state.activate(manifest())
        assertTrue(state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)
    }

    fun `test deactivate clears manifest`() {
        val state = project.service<RecorderState>()
        state.activate(manifest())
        state.deactivate()
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    fun `test activity activates state when loader returns Active`() = runBlocking {
        val m = manifest("hw07")
        val activity = RecorderActivationActivity { _, _ -> ManifestActivation.Active(m) }
        activity.execute(project)
        val state = project.service<RecorderState>()
        assertTrue(state.isActive)
        assertEquals("hw07", state.manifest?.assignmentId)
    }

    fun `test activity leaves state inactive when loader returns Inactive`() = runBlocking {
        val state = project.service<RecorderState>()
        state.activate(manifest()) // pre-existing active state must be cleared
        val activity = RecorderActivationActivity { _, _ -> ManifestActivation.Inactive("no_manifest_file") }
        activity.execute(project)
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }
}
