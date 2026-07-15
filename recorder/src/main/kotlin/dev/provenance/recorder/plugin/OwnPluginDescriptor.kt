package dev.provenance.recorder.plugin

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.extensions.PluginDescriptor

/** Anchor whose class loader is, by construction, this plugin's own. */
private object PluginAnchor

/**
 * This plugin's own [PluginDescriptor], resolved from the class loader that loaded this plugin.
 *
 * The platform loads plugin classes through a `PluginAwareClassLoader`, which carries the owning
 * plugin's descriptor; casting to it is the SDK's documented replacement for the private
 * `PluginClassLoader` (see the Internal API Migration page). This route is deliberate rather than
 * incidental: as of 2026.2 (262), *every* descriptor lookup on `PluginManager` /
 * `PluginManagerCore` — `getPlugin`, `findEnabledPlugin`, `getPlugins`, `getPluginByClass` — is
 * marked `@ApiStatus.Internal` and must not be used by plugins, while
 * `PluginAwareClassLoader.getPluginDescriptor()` and [PluginDescriptor]'s `pluginPath`/`version`
 * accessors remain public API. Do not "simplify" this back to a PluginManager lookup.
 *
 * Only resolves *this* plugin — it cannot enumerate others. Enumerating installed plugins has no
 * public API on 262, which is why ext.snapshot is unwired; see the note in
 * [dev.provenance.recorder.session.RecorderSessionManager].
 *
 * Returns null when this class was not loaded by a plugin class loader — i.e. outside a real IDE,
 * such as a plain JVM unit test — so callers must handle absence rather than assume presence.
 */
internal fun ownPluginDescriptor(): PluginDescriptor? =
    (PluginAnchor::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor
