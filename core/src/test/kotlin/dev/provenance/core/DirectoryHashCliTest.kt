package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the CLI wrapper to delegate to [DirectoryHash.sha256] without spawning a subprocess
 * (the subprocess path is exercised by the Gradle `computeExtensionHash` task itself).
 */
class DirectoryHashCliTest {
    @Test
    fun `computes the same hash as DirectoryHash directly`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "hello")
        assertEquals(DirectoryHash.sha256(dir), directoryHashCliCompute(dir.toString()))
    }
}
