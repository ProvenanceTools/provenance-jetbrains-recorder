package dev.provenance.recorder.startup

import dev.provenance.recorder.io.AtomicMover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.streams.toList

/**
 * Real [RecoveryDeps] over java.nio.file. Blocking file I/O runs on Dispatchers.IO so a
 * call at plugin activation never stalls the UI thread (the reason the seam is suspend).
 *
 * rename() prefers ATOMIC_MOVE (the quarantine target is same-directory, so an atomic
 * same-filesystem rename holds on all target platforms) and falls back to a plain move
 * if the filesystem does not support atomic moves — quarantine correctness does not
 * depend on atomicity, only on the file ending up out of the live path.
 *
 * "Does not support" is not always signalled as AtomicMoveNotSupportedException: JetBrains'
 * IJent nio provider (used when a Windows IDE opens a project on the WSL filesystem)
 * answers unimplemented operations with kotlin.NotImplementedError, an *Error*. This runs
 * upstream of session start in startFromActivation(), so an escaping Error here kills
 * activation before recording can begin — the same failure mode fixed in AtomicWrite.kt.
 * The atomic attempt is not latched off: rename() runs at most a handful of times per
 * activation, so there is no repeated-throw cost worth carrying state for.
 */
class NioRecoveryDeps internal constructor(
    override val provenanceDir: String,
    /**
     * This session's `student_ref`, or null when the student holds no verifying enrollment.
     * The ownership signal — see `SlogOwnership.kt`. Defaulted to null so the existing
     * unenrolled-path call sites keep exactly the behaviour they had.
     */
    override val ownStudentRef: String? = null,
    /** Test seam for the atomic attempt; production is a plain ATOMIC_MOVE. */
    private val atomicMove: AtomicMover = AtomicMover { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    },
) : RecoveryDeps {
    override suspend fun readSlogFile(path: String): SlogReadResult = withContext(Dispatchers.IO) {
        try {
            SlogReadResult.Ok(Files.readString(Path.of(path)))
        } catch (_: NoSuchFileException) {
            SlogReadResult.Err("not_found")
        } catch (_: IOException) {
            SlogReadResult.Err("read_error")
        }
    }

    override suspend fun rename(from: String, to: String) {
        withContext(Dispatchers.IO) {
            val src = Path.of(from)
            val dst = Path.of(to)
            try {
                atomicMove.move(src, dst)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(src, dst)
            } catch (_: NotImplementedError) {
                Files.move(src, dst)
            } catch (_: UnsupportedOperationException) {
                Files.move(src, dst)
            }
        }
    }

    override suspend fun listSlogFiles(dir: String): List<String> = withContext(Dispatchers.IO) {
        val path = Path.of(dir)
        if (!Files.isDirectory(path)) {
            emptyList()
        } else {
            Files.list(path).use { stream ->
                stream.toList()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".slog") }
                    .map { it.fileName.toString() }
            }
        }
    }

    override fun now(): Instant = Instant.now()
}
