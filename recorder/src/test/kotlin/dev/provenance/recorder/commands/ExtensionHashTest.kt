package dev.provenance.recorder.commands

import dev.provenance.core.DirectoryHash
import dev.provenance.core.Sha256
import dev.provenance.core.stagedDistributionExtensionHash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The reproducible directory-tree SHA-256 *algorithm* now lives in core/ (DirectoryHashTest
 * there is its exhaustive gate). This recorder-side test pins only the seal call site's
 * contract: [computeExtensionHash] must delegate to [DirectoryHash.sha256] over the given path,
 * unchanged. No IntelliJ Platform — a real temp dir suffices. ([computeInstalledExtensionHash]
 * needs a running sandbox IDE to resolve PluginManagerCore, so it is covered by the heavy
 * end-to-end seal gates, not here.)
 */
class ExtensionHashTest {
    private val temps = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("exthash").also { temps.add(it) }

    @After
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    @Test
    fun `computeExtensionHash delegates to core DirectoryHash for a given path`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("plugin.jar"), "fake-jar-bytes")
        Files.createDirectories(dir.resolve("lib"))
        Files.writeString(dir.resolve("lib/dep.jar"), "dep-bytes")
        assertEquals(DirectoryHash.sha256(dir), computeExtensionHash(dir))
    }

    /**
     * The invariant that makes `extension_hash` usable at all: the build/CI-time hash and the
     * seal-time hash are taken over the *same tree level*, so they are equal.
     *
     * They were not. `unpackDistributionForHash` extracts the distribution `.zip` — whose single
     * top-level entry is `recorder/` — into a staging directory, and the Gradle task handed
     * DirectoryHash the *staging* directory, so every relative path digested as `recorder/lib/...`.
     * At seal time [computeInstalledExtensionHash] hashes `PluginDescriptor.pluginPath`, i.e. the
     * installed plugin directory itself, so the same files digest as `lib/...`. Since the digest
     * covers `<relative-path>\0<file-bytes>`, the two could never match, and every allowlisted
     * build-time hash was a value no student's IDE would ever report.
     */
    @Test
    fun `build-time and seal-time extension_hash are taken over the same tree level`() {
        val staging = tempDir()
        // What `unpackDistributionForHash` produces: staging/<plugin-root>/...
        val installed = staging.resolve("recorder")
        Files.createDirectories(installed.resolve("lib/modules"))
        Files.writeString(installed.resolve("lib/recorder-0.2.0.jar"), "plugin-bytes")
        Files.writeString(installed.resolve("lib/dep.jar"), "dep-bytes")
        Files.writeString(installed.resolve("lib/modules/mod.jar"), "module-bytes")

        // Build/CI: the Gradle task hands the CLI the staging directory.
        val buildTime = stagedDistributionExtensionHash(staging)
        // Seal: computeInstalledExtensionHash hashes the installed plugin directory itself.
        val sealTime = computeExtensionHash(installed)

        assertEquals(sealTime, buildTime)
    }

    @Test
    fun `empty or missing plugin path hashes to the sha256 of empty`() {
        assertEquals(Sha256.hex(ByteArray(0)), computeExtensionHash(tempDir()))
        assertEquals(Sha256.hex(ByteArray(0)), computeExtensionHash(tempDir().resolve("does-not-exist")))
    }
}
