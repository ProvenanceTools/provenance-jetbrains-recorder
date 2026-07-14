package dev.provenance.recorder.commands

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.provenance.core.DirectoryHash
import java.nio.file.Path

/**
 * The bundle manifest's `extension_hash` = a reproducible SHA-256 over the recorder's installed
 * distribution *file tree*. The algorithm itself is pure and lives in `core/`
 * ([DirectoryHash]); this file is only the IntelliJ-Platform-aware call site that resolves the
 * running plugin's own installed path.
 *
 * Deliberately split in two:
 *   - [computeExtensionHash] (path): pure, IntelliJ-Platform-free, directly testable.
 *   - [computeInstalledExtensionHash] (pluginId): resolves this plugin's installed directory via
 *     PluginManagerCore and delegates. This is the seal-command call site.
 *
 * Both paths — and the build/CI-time `computeExtensionHash` Gradle task, which hashes the
 * extracted plugin distribution via [DirectoryHash]'s CLI entrypoint — go through the same
 * [DirectoryHash.sha256], so a runtime-computed hash and a build-time-computed hash can never
 * drift. Never reimplement the algorithm here.
 */
fun computeExtensionHash(pluginPath: Path): String = DirectoryHash.sha256(pluginPath)

/**
 * Resolve the running plugin's installed distribution path and hash it. Used by the seal action.
 * Throws if the plugin descriptor can't be resolved (a "should never happen" for a loaded plugin).
 */
fun computeInstalledExtensionHash(pluginId: String): String {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId))
        ?: error("plugin not found: $pluginId")
    return computeExtensionHash(plugin.pluginPath)
}
