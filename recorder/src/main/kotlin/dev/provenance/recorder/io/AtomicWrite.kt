package dev.provenance.recorder.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Write-temp-then-rename. Never partial-writes the target file (CLAUDE.md).
 * Mirrors the VS Code recorder's atomic-write.ts, including the "never mask the
 * original error" rule. Used for whole-file writes (.slog.meta, manifest.json,
 * manifest.sig); the .slog itself is append-only via SessionWriter.
 */
fun atomicWriteFile(targetPath: Path, contents: ByteArray) {
    val randomHex = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
    val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.$randomHex.tmp")
    try {
        Files.newByteChannel(
            tmpPath,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ),
        ).use { channel ->
            channel.write(ByteBuffer.wrap(contents))
            // fsync the file data to disk before the rename (the JVM equivalent of fh.sync()).
            if (channel is FileChannel) channel.force(true)
        }
        Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (original: Exception) {
        try {
            Files.deleteIfExists(tmpPath)
        } catch (_: Exception) {
            // best-effort cleanup; never mask the original error
        }
        throw original
    }
}

fun atomicWriteFile(targetPath: Path, contents: String) =
    atomicWriteFile(targetPath, contents.toByteArray(StandardCharsets.UTF_8))
