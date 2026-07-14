package dev.provenance.recorder.wiring.snapshot

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import dev.provenance.core.ExtActivatePayload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Pure JUnit 4 — the descriptor→payload transform and the listener's emission on a simulated
 * plugin-load are tested without a live plugin subsystem (a proxy IdeaPluginDescriptor supplies
 * the two fields the listener reads).
 */
class ExtActivateWiringTest {

    private fun descriptor(id: String, version: String?): IdeaPluginDescriptor =
        Proxy.newProxyInstance(
            IdeaPluginDescriptor::class.java.classLoader,
            arrayOf(IdeaPluginDescriptor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginId" -> PluginId.getId(id)
                "getVersion" -> version
                else -> null
            }
        } as IdeaPluginDescriptor

    @Test
    fun `payloadFor maps id and version`() {
        assertEquals(ExtActivatePayload("a.b.c", "1.0"), ExtActivateWiring.payloadFor("a.b.c", "1.0"))
    }

    @Test
    fun `payloadFor falls back to unknown when version is null`() {
        assertEquals(ExtActivatePayload("a.b.c", "unknown"), ExtActivateWiring.payloadFor("a.b.c", null))
    }

    @Test
    fun `listener emits ext_activate on a simulated plugin load`() {
        val emitted = mutableListOf<ExtActivatePayload>()
        val listener = ExtActivateWiring.listener { emitted.add(it) }
        listener.pluginLoaded(descriptor("com.example.copilot", "1.2.3"))
        assertEquals(1, emitted.size)
        assertEquals(ExtActivatePayload("com.example.copilot", "1.2.3"), emitted[0])
    }

    @Test
    fun `listener does not emit for plugin unload`() {
        val emitted = mutableListOf<ExtActivatePayload>()
        val listener = ExtActivateWiring.listener { emitted.add(it) }
        // pluginUnloaded is intentionally unmapped (no ext.deactivate in the format).
        listener.pluginUnloaded(descriptor("com.example.copilot", "1.2.3"), false)
        assertEquals(0, emitted.size)
    }
}
