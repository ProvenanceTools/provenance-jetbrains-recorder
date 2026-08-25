package dev.provenance.recorder.wiring

import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path

/**
 * The ONE directory listener behind [PeerWatcher] (writer contract rule 1).
 *
 * A partner's `.slog` filename is a uuid minted on their machine, so a per-file listener is
 * not even expressible; and only a directory-level listener sees a file APPEAR, which is the
 * case peer witnessing exists for. This is deliberately distinct from
 * [dev.provenance.recorder.watch.VfsExternalChangeListener], which watches the student's own
 * source under the assignment root: that one reads content and classifies external edits,
 * this one watches provenance artifacts and does nothing but note a name.
 *
 * **RULE 2: the callback does no I/O.** `after()` runs on the EDT inside a write action, so
 * the entire handler is a nio-path resolve, a parent comparison, and
 * [PeerWatcher.enqueue] — two string comparisons and a `Set.add`. No read, no hash, no parse:
 * hashing a partner's multi-megabyte log here would blow the <1 ms p99 handler budget (PRD
 * §4.7) on the EDT, of all places. All of that happens in [PeerWatcher.drain], on the
 * checkpoint cadence.
 *
 * **This listener is a promptness signal, not the source of truth.** IntelliJ's VFS is a
 * cached layer that may not have refreshed when a `git pull` lands in an external terminal, so
 * [PeerWatcher.drain] also sweeps the directory. See [PeerWatcher]'s docstring.
 *
 * Nothing here can throw into the platform: a malformed event is skipped.
 */
class ProvenanceDirVfsListener(
    private val provenanceDir: Path,
    private val watcher: PeerWatcher,
) : BulkFileListener {

    private val normalizedDir: Path = runCatching { provenanceDir.normalize() }.getOrDefault(provenanceDir)

    override fun after(events: List<VFileEvent>) {
        for (event in events) {
            val file = event.file ?: continue
            if (!file.isInLocalFileSystem) continue
            val nio = runCatching { file.toNioPath() }.getOrNull() ?: continue
            // Only direct children of THIS session's `.provenance/`. A nested directory, a
            // sibling assignment's `.provenance/`, and anything outside the workspace are all
            // out of scope.
            val parent = runCatching { nio.normalize().parent }.getOrNull() ?: continue
            if (parent != normalizedDir) continue
            watcher.enqueue(nio.fileName.toString())
        }
    }
}
