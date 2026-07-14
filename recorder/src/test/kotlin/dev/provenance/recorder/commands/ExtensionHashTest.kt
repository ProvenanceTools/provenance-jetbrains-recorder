package dev.provenance.recorder.commands

import dev.provenance.core.DirectoryHash
import dev.provenance.core.Sha256
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

    @Test
    fun `empty or missing plugin path hashes to the sha256 of empty`() {
        assertEquals(Sha256.hex(ByteArray(0)), computeExtensionHash(tempDir()))
        assertEquals(Sha256.hex(ByteArray(0)), computeExtensionHash(tempDir().resolve("does-not-exist")))
    }
}
