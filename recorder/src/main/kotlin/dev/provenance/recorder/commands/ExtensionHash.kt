package dev.provenance.recorder.commands

import dev.provenance.core.DirectoryHash
import dev.provenance.recorder.plugin.ownPluginDescriptor
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
 *     [ownPluginDescriptor] and delegates. This is the seal-command call site.
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
    val plugin = ownPluginDescriptor() ?: error("plugin not found: $pluginId")
    // The hash is producer identity: it must be *this* plugin's tree, never a neighbour's.
    check(plugin.pluginId.idString == pluginId) {
        "resolved own descriptor ${plugin.pluginId.idString}, expected $pluginId"
    }
    return computeExtensionHash(plugin.pluginPath)
}
