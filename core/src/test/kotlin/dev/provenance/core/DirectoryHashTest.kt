package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-logic test for the reproducible directory-tree SHA-256 that produces the bundle's
 * extension_hash. Mirrors the VS Code recorder's extension-hash.ts: sorted relative path +
 * NUL + file bytes, one digest. No IntelliJ Platform — a plain temp dir suffices.
 */
class DirectoryHashTest {

    @Test
    fun `empty directory hashes to sha256 of empty bytes`(@TempDir dir: Path) {
        assertEquals(Sha256.hex(ByteArray(0)), DirectoryHash.sha256(dir))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DirectoryHash.sha256(dir),
        )
    }

    @Test
    fun `missing directory hashes the same as an empty directory`(@TempDir dir: Path) {
        assertEquals(DirectoryHash.sha256(dir), DirectoryHash.sha256(dir.resolve("does-not-exist")))
    }

    @Test
    fun `is deterministic regardless of creation order (sorted by relative path)`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("b.txt"), "beta")
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/a.txt"), "alpha")
        val h1 = DirectoryHash.sha256(dir)

        val dir2 = Files.createTempDirectory("dirhash-order")
        Files.createDirectories(dir2.resolve("sub"))
        Files.writeString(dir2.resolve("sub/a.txt"), "alpha")
        Files.writeString(dir2.resolve("b.txt"), "beta")
        val h2 = DirectoryHash.sha256(dir2)

        assertEquals(h1, h2)
    }

    @Test
    fun `nested relative paths change the hash`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/file.txt"), "nested")
        val nested = DirectoryHash.sha256(dir)

        val flat = Files.createTempDirectory("dirhash-flat")
        Files.writeString(flat.resolve("file.txt"), "nested")
        assertNotEquals(nested, DirectoryHash.sha256(flat))
    }

    @Test
    fun `changing file content changes the hash`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "one")
        val h1 = DirectoryHash.sha256(dir)
        Files.writeString(dir.resolve("a.txt"), "two")
        assertNotEquals(h1, DirectoryHash.sha256(dir))
    }

    @Test
    fun `matches the hand-computed digest for a known tree`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("x"), "X")
        // Single file "x" with bytes "X": digest over "x" + 0x00 + "X".
        val expected = Sha256.hex("x".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + "X".toByteArray(Charsets.UTF_8))
        assertEquals(expected, DirectoryHash.sha256(dir))
    }

    @Test
    fun `produces 64 lowercase hex chars`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("f"), "data")
        val h = DirectoryHash.sha256(dir)
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
    }
}
