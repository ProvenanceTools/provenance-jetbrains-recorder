package dev.provenance.recorder.commands

import dev.provenance.core.Sha256
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-logic test (JUnit 4) for the reproducible directory-tree SHA-256 that produces the
 * bundle's extension_hash. Mirrors the VS Code recorder's extension-hash.ts: sorted relative
 * path + NUL + file bytes, one digest. No IntelliJ Platform — a real temp dir suffices.
 */
class ExtensionHashTest {
    private val temps = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("exthash").also { temps.add(it) }

    @After
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    @Test
    fun `empty or missing directory hashes to the sha256 of empty`() {
        assertEquals(Sha256.hex(ByteArray(0)), DirectoryHash.sha256(tempDir()))
        assertEquals(Sha256.hex(ByteArray(0)), DirectoryHash.sha256(tempDir().resolve("does-not-exist")))
    }

    @Test
    fun `hash is deterministic and order-independent (sorted by relative path)`() {
        val a = tempDir()
        Files.writeString(a.resolve("b.txt"), "beta")
        Files.createDirectories(a.resolve("sub"))
        Files.writeString(a.resolve("sub/a.txt"), "alpha")

        val b = tempDir()
        // Same tree, files created in the opposite order.
        Files.createDirectories(b.resolve("sub"))
        Files.writeString(b.resolve("sub/a.txt"), "alpha")
        Files.writeString(b.resolve("b.txt"), "beta")

        assertEquals(DirectoryHash.sha256(a), DirectoryHash.sha256(b))
    }

    @Test
    fun `content change changes the hash`() {
        val a = tempDir()
        Files.writeString(a.resolve("f.txt"), "one")
        val h1 = DirectoryHash.sha256(a)
        Files.writeString(a.resolve("f.txt"), "two")
        assertNotEquals(h1, DirectoryHash.sha256(a))
    }

    @Test
    fun `matches the hand-computed digest for a known tree`() {
        val root = tempDir()
        Files.writeString(root.resolve("x"), "X")
        // Single file "x" with bytes "X": digest over "x" + 0x00 + "X".
        val expected = Sha256.hex("x".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + "X".toByteArray(Charsets.UTF_8))
        assertEquals(expected, DirectoryHash.sha256(root))
    }

    @Test
    fun `produces 64 lowercase hex chars`() {
        val root = tempDir()
        Files.writeString(root.resolve("f"), "data")
        val h = DirectoryHash.sha256(root)
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
    }
}
