package dev.provenance.recorder.commands

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The bundle manifest's `extension_hash` = a reproducible SHA-256 over the recorder's installed
 * distribution *file tree* — a Kotlin port of the VS Code recorder's computeExtensionHash
 * (packages/recorder/src/commands/extension-hash.ts). Hashing the extracted tree (not the raw
 * `.zip` bytes) is deterministic regardless of how the archive was produced, and it is the only
 * thing computable at runtime, since the running IDE has the plugin's files on disk, not the
 * installer zip. See design.md §6 (read its "distribution .zip" as shorthand for the tree hash,
 * the same shorthand the analyzer's allowlist doc-comment uses).
 *
 * NOTE: the forthcoming build/dist plan formalizes this with a CI-time Gradle task that hashes
 * the *signed* distribution and publishes the value to the analyzer allowlist; this runtime
 * implementation is the seal-time call site of the same algorithm. Both must agree byte-for-byte.
 */
object DirectoryHash {
    /**
     * Walk [root] recursively, collect regular files only (symlinks/dirs skipped), sort by the
     * path relative to [root] using ordinal String comparison, then stream
     * `<relative-path-utf8> + 0x00 + <file-bytes>` through one SHA-256 digest. An empty or
     * missing directory hashes to the SHA-256 of the empty byte sequence.
     */
    fun sha256(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (Files.isDirectory(root)) {
            val files = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it to root.relativize(it).toString().replace('\\', '/') }
                    .toList()
            }
            for ((path, rel) in files.sortedBy { it.second }) {
                digest.update(rel.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(Files.readAllBytes(path))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Pure, IntelliJ-Platform-free entry point (takes the path as a parameter so it is testable
 * against a plain temp dir). [computeInstalledExtensionHash] resolves the real installed path.
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
