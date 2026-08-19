package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Manifest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

class RecorderStateTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            project.service<RecorderState>().deactivateAll()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(assignmentId: String = "hw03") =
        Manifest(assignmentId, "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))

    private fun root(name: String): Path = Paths.get("/ws-$name")

    fun `test isActive is false by default`() {
        assertFalse(project.service<RecorderState>().isActive)
    }

    fun `test activate then isActive is true, manifest is stored`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        assertTrue(state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)
    }

    fun `test deactivateAll clears every manifest`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.deactivateAll()
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    fun `test deactivate one root leaves the other active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        state.deactivate(root("a"))
        assertTrue(state.isActive)
        assertEquals(setOf("hw-b"), state.activeManifests.values.map { it.assignmentId }.toSet())
    }

    fun `test manifest is null when more than one assignment is active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        assertNull("manifest is the single-assignment convenience; ambiguous with 2 active", state.manifest)
        assertEquals(2, state.activeManifests.size)
    }

    fun `test markDegraded keeps the root active and records the reason`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "NotImplementedError: an operation is not implemented")
        assertTrue("a degraded root stays activated (the indicator must keep rendering)", state.isActive)
        assertTrue(state.isDegraded(root("a")))
        assertEquals(
            "NotImplementedError: an operation is not implemented",
            state.degradedRoots[root("a").normalize()],
        )
    }

    fun `test re-activating a root clears its stale degraded marker`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "boom")
        state.activate(root("a"), manifest())
        assertFalse("a fresh activation supersedes the previous failure", state.isDegraded(root("a")))
        assertTrue(state.degradedRoots.isEmpty())
    }

    fun `test deactivate clears the degraded marker for that root only`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        state.markDegraded(root("a"), "boom")
        state.markDegraded(root("b"), "boom")
        state.deactivate(root("a"))
        assertFalse(state.isDegraded(root("a")))
        assertTrue(state.isDegraded(root("b")))
    }

    fun `test deactivateAll clears every degraded marker`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "boom")
        state.deactivateAll()
        assertTrue(state.degradedRoots.isEmpty())
    }

    fun `test activity activates state for every discovered root when discoverer returns Active manifests`() = runBlocking {
        val m = myFixture.addFileToProject("hw07/.provenance-manifest", "{}").virtualFile.parent
        val activity = RecorderActivationActivity { _, _ -> listOf(DiscoveredManifest(m, manifest("hw07"))) }
        activity.execute(project)
        val state = project.service<RecorderState>()
        assertTrue(state.isActive)
        assertEquals("hw07", state.manifest?.assignmentId)
    }

    fun `test a root with no resolvable filesystem path is activated but marked degraded`() = runBlocking {
        // A discovered root whose VirtualFile has no nio path (the light fixture's in-memory VFS
        // here; a non-local project root in production) is activated but can NEVER record —
        // activation only attempts a session start for a root it can resolve. Left unmarked it
        // rendered the NORMAL "recording" indicator while nothing was ever written: the exact
        // active-but-silent failure the degraded indicator exists to eliminate.
        val m = myFixture.addFileToProject("hw09/.provenance-manifest", "{}").virtualFile.parent
        assertNull(
            "guard: this case is vacuous if the fixture's root DOES resolve to an nio path",
            runCatching { m.toNioPath() }.getOrNull(),
        )
        val activity = RecorderActivationActivity { _, _ -> listOf(DiscoveredManifest(m, manifest("hw09"))) }

        activity.execute(project)

        val state = project.service<RecorderState>()
        val key = Paths.get(m.path)
        assertTrue("the root must stay activated so an indicator still renders", state.isActive)
        assertTrue("a root that can never record must not read as recording", state.isDegraded(key))
        assertNotNull("the degraded reason must say why", state.degradedRoots[key.normalize()])
    }

    fun `test activity leaves state inactive when discoverer returns nothing`() = runBlocking {
        val state = project.service<RecorderState>()
        state.activate(root("stale"), manifest())
        val activity = RecorderActivationActivity { _, _ -> emptyList() }
        activity.execute(project)
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }
}
