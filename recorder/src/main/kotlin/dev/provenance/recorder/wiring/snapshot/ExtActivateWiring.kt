package dev.provenance.recorder.wiring.snapshot

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import dev.provenance.core.ExtActivatePayload

/**
 * ext.activate wiring (recorder PRD §4.2: "Another extension activates while we're recording").
 * Ported from the VS Code recorder's extension-activation.ts.
 *
 * IntelliJ ⇄ VS Code semantic mapping (documented per the port's "faithful, not identical"
 * mandate): VS Code has no activation event and *polls* `vscode.extensions.all`, emitting
 * ext.activate on an extension's inactive→active transition. IntelliJ instead fires
 * [DynamicPluginListener.pluginLoaded] when a plugin is dynamically installed/enabled *while the
 * IDE is running* — which is the true analogue of "a new extension became active mid-session"
 * (and cleaner than polling). The important caveat: most IntelliJ plugins load at startup, before
 * a recording session exists, so in practice this fires rarely — exactly when it matters (a
 * student installs/enables, say, an AI assistant plugin during the assignment). [pluginUnloaded]
 * is deliberately not mapped: the format has no ext.deactivate.
 *
 * The pure descriptor→payload transform ([payloadFor]) is kept separate from the platform
 * listener ([listener]) so it is unit-testable without a live plugin subsystem (CLAUDE.md).
 */
object ExtActivateWiring {
    /** Pure transform: a loaded plugin's identity → ext.activate payload. */
    fun payloadFor(id: String, version: String?): ExtActivatePayload =
        ExtActivatePayload(id = id, version = version ?: "unknown")

    /**
     * The [DynamicPluginListener] that emits ext.activate on each mid-session plugin load.
     * Subscribe it to [DynamicPluginListener.TOPIC] on the application message bus, tied to the
     * session Disposable so it stops when the session ends.
     */
    fun listener(emit: (ExtActivatePayload) -> Unit): DynamicPluginListener =
        object : DynamicPluginListener {
            override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
                emit(payloadFor(pluginDescriptor.pluginId.idString, pluginDescriptor.version))
            }
        }
}
