package dev.provenance.recorder.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.provenance.core.FsExternalChangePayload
import java.nio.file.Path

/**
 * Path 2 of PRD §4.5 — the BulkFileListener on VirtualFileManager.VFS_CHANGES. Catches
 * on-disk changes IntelliJ's VFS becomes aware of asynchronously: the native file
 * watcher, and the refresh-on-frame-activation backstop (the alt-tab-back scenario).
 *
 * Why BulkFileListener and not AsyncFileListener: AsyncFileListener.prepareChange runs
 * BEFORE the VFS event is applied and cannot see the new content; we need the post-change
 * on-disk bytes to hash and diff, which after() provides.
 *
 * after() runs on the EDT inside a write action, so it must be cheap: it only triages
 * (resolve relative path, classify event type, read the isFromSave flag) and hands the
 * work to [dispatch] for the actual content read + classify + emit off the write action.
 *
 * Dedup (the reconciliation with Plan 4's editor saves):
 *  - isFromSave() == true  → the IDE's own mediated write (FileDocumentManagerImpl opts in
 *    to SavingRequestor). Routed to the save-time check (path 1). This is an exact flag,
 *    strictly better than VS Code's 250ms timing window.
 *  - isFromSave() == false → a genuine external write (CLI / git / other editor) → the
 *    anti-CLI signal (path 2). A secondary [isRecentEditorChange] guard is available for
 *    saves that trigger a second, differently-requestored write (e.g. format-on-save); it
 *    defaults to off since isFromSave is the primary mechanism and Plan 4 does not yet
 *    track per-path doc.change timestamps.
 */
class VfsExternalChangeListener(
    private val workspaceRoot: Path,
    private val engine: ExternalChangeEngine,
    private val saveChecker: SaveTimeExternalChangeChecker,
    private val emit: (FsExternalChangePayload) -> Unit,
    private val isRecentEditorChange: (String) -> Boolean = { false },
    private val readDisk: (VirtualFile) -> String = ::readVfsText,
    private val dispatch: (() -> Unit) -> Unit = DEFAULT_DISPATCH,
) : BulkFileListener {

    private enum class Kind { MODIFY, CREATE, DELETE }
    private data class Item(val relPath: String, val kind: Kind, val file: VirtualFile?, val fromSave: Boolean)

    override fun after(events: List<VFileEvent>) {
        val items = ArrayList<Item>(events.size)
        for (e in events) {
            val vf = e.file ?: continue
            val rel = relativePathOf(vf, workspaceRoot) ?: continue
            if (!engine.registry.isWatched(rel)) continue // session scope filter
            val kind = when (e) {
                is VFileContentChangeEvent -> Kind.MODIFY
                is VFileCreateEvent -> Kind.CREATE
                is VFileDeleteEvent -> Kind.DELETE
                else -> continue // rename/move/property — out of scope
            }
            items.add(Item(rel, kind, vf, e.isFromSave))
        }
        if (items.isEmpty()) return
        dispatch { for (item in items) process(item) }
    }

    private fun process(item: Item) {
        when (item.kind) {
            Kind.MODIFY -> {
                val content = runCatching { readDisk(item.file!!) }.getOrNull() ?: return
                if (item.fromSave) {
                    // Editor save — path 1 semantics (format-on-save / save race).
                    saveChecker.checkAfterSave(item.relPath, item.file!!)
                } else {
                    if (isRecentEditorChange(item.relPath)) return // secondary dedup
                    engine.onExternalModify(item.relPath, content)?.let(emit)
                }
            }
            Kind.CREATE -> {
                val content = runCatching { readDisk(item.file!!) }.getOrNull() ?: return
                engine.onExternalCreate(item.relPath, content)?.let(emit)
            }
            Kind.DELETE -> engine.onExternalDelete(item.relPath)?.let(emit)
        }
    }

    companion object {
        /**
         * Production dispatch: off the EDT/write-action, inside a read action (background-
         * thread VFS content reads require one). Do NOT read content in after() itself.
         */
        val DEFAULT_DISPATCH: (() -> Unit) -> Unit = { work ->
            ApplicationManager.getApplication().executeOnPooledThread {
                ApplicationManager.getApplication().runReadAction { work() }
            }
        }
    }
}
