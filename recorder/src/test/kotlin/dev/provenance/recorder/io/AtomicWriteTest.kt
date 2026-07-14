package dev.provenance.recorder.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AtomicWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trips bytes`() {
        val target = tmp.root.toPath().resolve("out.bin")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        atomicWriteFile(target, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun `round trips string as UTF-8`() {
        val target = tmp.root.toPath().resolve("out.txt")
        atomicWriteFile(target, "héllo €")
        assertEquals("héllo €", String(Files.readAllBytes(target), Charsets.UTF_8))
    }

    @Test
    fun `overwrite leaves only final content and no tmp files`() {
        val target = tmp.root.toPath().resolve("out.txt")
        atomicWriteFile(target, "first")
        atomicWriteFile(target, "second")
        assertEquals("second", String(Files.readAllBytes(target), Charsets.UTF_8))
        val siblings = Files.list(tmp.root.toPath()).use { it.toList() }
        assertEquals(1, siblings.size)
        assertEquals("out.txt", siblings[0].fileName.toString())
    }

    @Test
    fun `failure leaves no tmp file and rethrows original`() {
        // Target's parent does not exist → newByteChannel(CREATE) fails.
        val target = tmp.root.toPath().resolve("nope").resolve("out.txt")
        var threw = false
        try {
            atomicWriteFile(target, "x")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected an exception", threw)
        // No leftover .tmp anywhere in the temp root.
        val leftovers = Files.walk(tmp.root.toPath()).use { s ->
            s.filter { it.fileName?.toString()?.endsWith(".tmp") == true }.toList()
        }
        assertTrue("no .tmp should survive, found: $leftovers", leftovers.isEmpty())
        assertFalse(Files.exists(target))
    }
}
