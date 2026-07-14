package dev.provenance.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Reproducible SHA-256 over an entire file tree — the Kotlin port of the VS Code
 * recorder's `computeExtensionHash` (packages/recorder/src/commands/extension-hash.ts).
 *
 * This is the bundle manifest's `extension_hash` algorithm. Hashing the extracted tree
 * (not the raw distribution `.zip` bytes) is deterministic regardless of how the archive
 * was produced — zip byte layout (timestamps, compression, central-directory order) is not
 * reproducible across tools — and it is the only thing computable at runtime, since a running
 * IDE has the plugin's files on disk, not the installer zip. Read design.md §6's "distribution
 * .zip" as shorthand for this tree hash (the same shorthand the analyzer's allowlist
 * doc-comment uses).
 *
 * Lives in `core/` (pure JVM: `java.nio` + `java.security`, no IntelliJ Platform) so the two
 * call sites can never drift:
 *   - seal time, over the plugin's own installed directory
 *     (recorder/ `computeInstalledExtensionHash` resolves that via PluginManagerCore); and
 *   - build/CI time, over the extracted plugin distribution
 *     (recorder/build.gradle.kts's `computeExtensionHash` task, via [DirectoryHashCli]).
 *
 * Algorithm (matches the TS original):
 *   1. Recursively walk [root]; collect regular files only (symlinks/dirs skipped).
 *   2. Sort by path relative to [root] using ordinal String comparison — NOT locale-sensitive
 *      collation, so two machines with different readdir orders produce the same hash.
 *   3. For each file, in sorted order: digest.update(relativePathUtf8), digest.update(0x00),
 *      digest.update(fileBytes).
 *   4. Return the lowercase hex digest. A missing or empty directory hashes to the SHA-256 of
 *      the empty byte sequence (`e3b0c442...b855`).
 */
object DirectoryHash {
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
