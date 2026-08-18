package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the build/CI-time `extension_hash` entrypoint without spawning a subprocess (the
 * subprocess path is exercised by the Gradle `computeExtensionHash` task itself).
 *
 * The load-bearing property: the hash is taken over the distribution root *inside* the
 * extraction staging directory, because that is the tree an installed plugin reports at seal
 * time. The cross-call-site equality itself is pinned by recorder/'s ExtensionHashTest.
 */
class ExtensionHashCliTest {

    private fun stageDistribution(staging: Path, rootName: String = "recorder"): Path {
        val root = staging.resolve(rootName)
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("lib/recorder.jar"), "plugin-bytes")
        return root
    }

    @Test
    fun `hashes the distribution root inside the staging dir, not the staging dir`(@TempDir staging: Path) {
        val root = stageDistribution(staging)
        assertEquals(DirectoryHash.sha256(root), stagedDistributionExtensionHash(staging))
        // Guard against the regression this test exists for: the staging-level hash differs,
        // and is the value no installed plugin can ever reproduce.
        assertNotEquals(DirectoryHash.sha256(staging), stagedDistributionExtensionHash(staging))
    }

    @Test
    fun `resolves the single child directory as the distribution root`(@TempDir staging: Path) {
        val root = stageDistribution(staging)
        assertEquals(root, pluginDistributionRoot(staging))
    }

    @Test
    fun `fails loudly when the staging dir is empty`(@TempDir staging: Path) {
        val e = assertThrows(PluginDistributionLayoutException::class.java) {
            stagedDistributionExtensionHash(staging)
        }
        assertTrue(e.message!!.contains("exactly one child directory"), e.message)
    }

    @Test
    fun `fails loudly when the staging dir holds more than one child directory`(@TempDir staging: Path) {
        stageDistribution(staging, "recorder")
        stageDistribution(staging, "other-plugin")
        val e = assertThrows(PluginDistributionLayoutException::class.java) {
            stagedDistributionExtensionHash(staging)
        }
        assertTrue(e.message!!.contains("recorder/") && e.message!!.contains("other-plugin/"), e.message)
    }

    @Test
    fun `fails loudly on a stray top-level file that would be silently left out of the hash`(
        @TempDir staging: Path,
    ) {
        stageDistribution(staging)
        Files.writeString(staging.resolve("stray.txt"), "not part of any plugin")
        val e = assertThrows(PluginDistributionLayoutException::class.java) {
            stagedDistributionExtensionHash(staging)
        }
        assertTrue(e.message!!.contains("stray.txt"), e.message)
    }

    @Test
    fun `fails loudly when the staging dir does not exist`(@TempDir staging: Path) {
        assertThrows(PluginDistributionLayoutException::class.java) {
            stagedDistributionExtensionHash(staging.resolve("never-extracted"))
        }
    }
}
