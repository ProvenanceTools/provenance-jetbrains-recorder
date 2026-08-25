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

/**
 * Temp-file open seam. Injectable so tests can fault-inject the open frame — the one
 * step of the write that runs before the tmp file is known to exist.
 */
internal fun interface ChannelOpener {
    fun open(path: Path): SeekableByteChannel
}

internal val REAL_CHANNEL_OPENER = ChannelOpener { path ->
    Files.newByteChannel(
        path,
        setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ),
    )
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
    opener: ChannelOpener = REAL_CHANNEL_OPENER,
) {
    val tmpPath = stageTemp(targetPath, contents, sync, opener)
    try {
        mover.move(tmpPath, targetPath)
    } catch (original: Throwable) {
        unlinkQuietly(tmpPath)
        throw original
    }
}

fun atomicWriteFile(targetPath: Path, contents: String) =
    atomicWriteFile(targetPath, contents.toByteArray(StandardCharsets.UTF_8))

/**
 * Write + fsync one temp file next to [targetPath] and return its path, WITHOUT renaming.
 * Shared by the single-file and multi-file writers so both use one tmp-naming scheme and
 * one cleanup discipline. On failure the temp it created is unlinked and the original
 * error rethrown unmasked.
 */
private fun stageTemp(
    targetPath: Path,
    contents: ByteArray,
    sync: BestEffortSync,
    opener: ChannelOpener,
): Path {
    val randomHex = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
    val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.$randomHex.tmp")
    try {
        opener.open(tmpPath).use { channel ->
            channel.write(ByteBuffer.wrap(contents))
            sync.force(channel)
        }
    } catch (original: Throwable) {
        unlinkQuietly(tmpPath)
        throw original
    }
    return tmpPath
}

/**
 * Throwable, not Exception. These handlers swallow nothing — they unlink a temp file and
 * rethrow — so catching broadly cannot hide a failure, unlike the narrow capability catches
 * in the seams above. An Exception-shaped handler let an Error (IJent answers unimplemented
 * operations with kotlin.NotImplementedError, and Files.newByteChannel / Files.deleteIfExists
 * are both plausible sources) skip the cleanup entirely: the crash that motivated this fix
 * left an orphaned .tmp in the student's .provenance/ directory.
 */
private fun unlinkQuietly(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: Throwable) {
        // best-effort cleanup; never mask the original error
    }
}

/**
 * Atomically write a SET of files that must AGREE with one another.
 *
 * There is no multi-file rename anywhere, so this cannot be a true transaction. What it does
 * is STAGE every file (write + fsync) before renaming any of them, so the only work left
 * between the renames is the renames themselves. That shrinks the window in which an observer
 * can see a mixed old/new set from "one whole file write plus an fsync" down to a single
 * filesystem operation.
 *
 * That window matters for the rolling seal: `manifest-<id>.json` and `manifest-<id>.sig` are
 * a signature over a payload, so a reader that catches a NEW json beside an OLD sig sees a
 * seal that does not verify. The window cannot be closed entirely (see RollingSeal.kt), only
 * minimised — and it is REPORTED rather than silently survived: a `git commit` landing inside
 * it produces a signature failure naming that session, which is the correct outcome for
 * evidence we cannot vouch for.
 *
 * Renames go through the same [BestEffortMove] as [atomicWriteFile], so a filesystem that
 * cannot do an atomic move (WSL via IJent) degrades to a plain move rather than failing the
 * write: every temp is already fully written, so a non-atomic rename still cannot expose a
 * partially written target — it only widens the window in which a target is briefly absent.
 *
 * On any failure — staging or renaming — every temp file this call created and did not yet
 * rename is best-effort unlinked and the original error rethrown unmasked. Files already
 * renamed are NOT rolled back: they are complete, fsynced files, and un-renaming them could
 * only replace good bytes with older ones.
 */
fun atomicWriteFilePair(files: List<Pair<Path, String>>) =
    atomicWriteFilePair(files, DEFAULT_SYNC, DEFAULT_MOVER)

internal fun atomicWriteFilePair(
    files: List<Pair<Path, String>>,
    sync: BestEffortSync,
    mover: BestEffortMove,
    opener: ChannelOpener = REAL_CHANNEL_OPENER,
) {
    /** tmp → target, in the order they must be committed. */
    val staged = ArrayList<Pair<Path, Path>>(files.size)
    var committed = 0
    try {
        // Phase 1 — stage. Every byte is on disk and fsynced before any rename runs.
        for ((targetPath, contents) in files) {
            staged.add(
                stageTemp(targetPath, contents.toByteArray(StandardCharsets.UTF_8), sync, opener) to targetPath,
            )
        }
        // Phase 2 — commit. Back-to-back renames, no intervening I/O.
        for ((tmpPath, targetPath) in staged) {
            mover.move(tmpPath, targetPath)
            committed++
        }
    } catch (original: Throwable) {
        for ((tmpPath, _) in staged.drop(committed)) unlinkQuietly(tmpPath)
        throw original
    }
}
