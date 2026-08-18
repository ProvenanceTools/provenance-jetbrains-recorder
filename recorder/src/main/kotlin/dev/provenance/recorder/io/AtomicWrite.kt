package dev.provenance.recorder.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * fsync seam: flushes a just-written temp file's data to disk. Injectable (like the
 * repo's clock / scheduler / ByteSink seams) so tests can exercise filesystems whose
 * channels cannot fsync at all.
 */
internal fun interface ChannelSync {
    fun force(channel: SeekableByteChannel)
}

/** Production fsync: the JVM equivalent of the VS Code recorder's `fh.sync()`. */
internal val REAL_CHANNEL_SYNC = ChannelSync { channel ->
    if (channel is FileChannel) channel.force(true)
}

/** Rename seam: the atomic (same-directory) rename of tmp → target. */
internal fun interface AtomicMover {
    fun move(from: Path, to: Path)
}

internal val REAL_ATOMIC_MOVER = AtomicMover { from, to ->
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Best-effort fsync. Atomicity of the write comes from write-temp-then-rename; the
 * fsync only buys crash durability on top of it. Losing durability on a filesystem
 * that cannot fsync is therefore the correct degradation — failing the write is not.
 *
 * Concretely: IntelliJ on Windows opening a project on the WSL filesystem
 * (\\wsl.localhost\...) routes I/O through JetBrains' IJent nio provider, whose
 * FileChannel.force() throws kotlin.NotImplementedError (an *Error*, so no `catch
 * (e: Exception)` upstream sees it). That escaped through MetaWriter into the startup
 * activity and killed activation outright — the plugin recorded nothing on WSL.
 *
 * Only the specific "this filesystem cannot do it" throwables are absorbed; anything
 * else propagates. NotImplementedError and UnsupportedOperationException are capability
 * signals and latch the seam off, so we do not pay the throw on every subsequent write.
 * An IOException is a transient device-level failure, not a capability answer, so it is
 * absorbed (the bytes are already written; the rename still has to happen) but never latched.
 */
internal class BestEffortSync(private val delegate: ChannelSync = REAL_CHANNEL_SYNC) {
    @Volatile
    private var unsupported = false

    fun force(channel: SeekableByteChannel) {
        if (unsupported) return
        try {
            delegate.force(channel)
        } catch (_: NotImplementedError) {
            unsupported = true
        } catch (_: UnsupportedOperationException) {
            unsupported = true
        } catch (_: IOException) {
            // transient; durability lost for this write only
        }
    }
}

/**
 * Atomic rename with a plain-move fallback. Same reasoning as [BestEffortSync]: the
 * tmp file is fully written before the rename, so a non-atomic rename still cannot
 * expose a partially written target to a reader of this file — it only widens the
 * window in which the target is briefly absent.
 *
 * A typed catch on AtomicMoveNotSupportedException is NOT sufficient here: IJent
 * signals unimplemented operations with kotlin.NotImplementedError, which is an Error.
 * Capability answers latch the seam off; every other failure (including the fallback's)
 * propagates to the caller untouched.
 */
internal class BestEffortMove(private val delegate: AtomicMover = REAL_ATOMIC_MOVER) {
    @Volatile
    private var atomicUnsupported = false

    fun move(from: Path, to: Path) {
        if (!atomicUnsupported) {
            try {
                delegate.move(from, to)
                return
            } catch (_: AtomicMoveNotSupportedException) {
                atomicUnsupported = true
            } catch (_: NotImplementedError) {
                atomicUnsupported = true
            } catch (_: UnsupportedOperationException) {
                atomicUnsupported = true
            }
        }
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}

private val DEFAULT_SYNC = BestEffortSync()
private val DEFAULT_MOVER = BestEffortMove()

/**
 * Write-temp-then-rename. Never partial-writes the target file (CLAUDE.md).
 * Mirrors the VS Code recorder's atomic-write.ts, including the "never mask the
 * original error" rule. Used for whole-file writes (.slog.meta, manifest.json,
 * manifest.sig); the .slog itself is append-only via SessionWriter.
 */
fun atomicWriteFile(targetPath: Path, contents: ByteArray) =
    atomicWriteFile(targetPath, contents, DEFAULT_SYNC, DEFAULT_MOVER)

internal fun atomicWriteFile(
    targetPath: Path,
    contents: ByteArray,
    sync: BestEffortSync,
    mover: BestEffortMove,
) {
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
            sync.force(channel)
        }
        mover.move(tmpPath, targetPath)
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
