package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.buildDocChangeDelta
import dev.provenance.recorder.events.buildDocChangePayload
import dev.provenance.recorder.events.buildDocClosePayload
import dev.provenance.recorder.events.buildDocOpenPayload
import dev.provenance.recorder.paste.PasteDecision
import dev.provenance.recorder.paste.toPastePayload
import dev.provenance.recorder.watch.VfsExternalChangeListener
import dev.provenance.recorder.watch.readVfsText
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * doc.open/change/save/close wiring (recorder PRD §4.2). Registered ONCE, project-scoped
 * (constructed by RecorderSessionManager, not per-session — see design.md's nested-manifest
 * discovery plan): a single global DocumentListener + FileEditorManagerListener +
 * post-save BulkFileListener, each resolving the *one* owning session per event via
 * [router], and dropping the event when no session owns the path. This is what makes
 * "no event escapes its assignment root" hold even for overlapping/nested roots — a per-
 * session listener filtered only by "is this under my root" would double-fire for a file
 * whose nearest ancestor differs from a farther, also-matching ancestor.
 *
 * [localFsOf]/[nioPathOf] are injectable so the transform is testable under a light fixture
 * whose files are not on the local file system; production uses the real VirtualFile checks.
 */
class DocWiring(
    private val project: Project,
    private val router: SessionRouter,
    parentDisposable: Disposable,
    private val localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    private val nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
    /**
     * How the post-save VFS listener gets off the EDT/write action before reading content.
     * Shares [VfsExternalChangeListener.DEFAULT_DISPATCH] so there is one definition of
     * "pooled thread inside a read action"; injectable so a test can run it inline.
     */
    private val vfsDispatch: (() -> Unit) -> Unit = VfsExternalChangeListener.DEFAULT_DISPATCH,
    /**
     * Reads what a just-completed save left on disk. VFS-mediated in production (see
     * [readVfsText]); injectable so a test can state "what the editor wrote" explicitly
     * instead of depending on filesystem timing.
     */
    private val readSavedText: (VirtualFile) -> String = ::readVfsText,
) {
    private val pending = WeakHashMap<Document, Range>()
    // Keyed by absolute nio path, NOT relative path: two different owning roots can each have
    // a file with the same relative name (e.g. "hw.py" under both cats/ and hog/), and a
    // relative-path key would wrongly treat the second as already-seen.
    private val seenPaths = mutableSetOf<Path>()

    /** One completed editor save, triaged on the EDT and carried to [vfsDispatch]. */
    private data class SavedFile(
        val file: VirtualFile,
        val relativePath: String,
        val sink: RecordableSessionSink,
    )

    // Listener registration AND the initial catch-up run as ONE EDT unit. That atomicity is the
    // ordering contract (recorder PRD §4.2.1, and see [runOnEdtAndWait]): a write action can only
    // run on the EDT, so nothing can mutate a document between the moment the DocumentListener
    // starts recording doc.change and the moment the catch-up reads each open file's doc.open
    // baseline. Registering off the EDT and catching up afterwards leaves exactly that window —
    // an edit landing in it is logged as a doc.change with no preceding doc.open, and is then
    // also folded into the baseline doc.open reads a moment later, so replay applies it twice.
    init {
        runOnEdtAndWait { installListenersAndCatchUp(parentDisposable) }
    }

    private fun installListenersAndCatchUp(parentDisposable: Disposable) {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    val sink = sinkFor(vf) ?: return
                    // Lazy doc.open for a document that no editor-TAB signal can ever reach.
                    // fileOpened and catchUpOpenFiles() are both tab-based (FileEditorManager),
                    // but this DocumentListener is application-wide and fires for ANY document —
                    // Replace in Files, a cross-file rename refactor, reformat on a directory,
                    // and code generation all mutate documents that were never opened in a tab.
                    // Without this, those files produce doc.change with no baseline, and replay
                    // reconstructs them from an empty buffer. The analyzer treats a missing
                    // doc.open as indeterminate rather than invalid, so it fails silently.
                    //
                    // It MUST be emitted here, not in documentChanged: this runs before the edit
                    // lands, so event.document still holds the PRE-change text. A post-change
                    // baseline would bake the edit in and then apply it again as a delta —
                    // replay would count it twice (the same hazard the init comment above
                    // describes for the registration/catch-up window). seenPaths de-dups against
                    // the tab-based path, so a file that later gets a tab is not re-emitted.
                    emitDocOpenFor(vf, sink, event.document)
                    pending[event.document] = rangeOf(event.document, event.offset, event.oldLength)
                }

                override fun documentChanged(event: DocumentEvent) {
                    val fdm = FileDocumentManager.getInstance()
                    val vf = fdm.getFile(event.document) ?: return
                    val sink = sinkFor(vf) ?: return
                    // Consume the pending range BEFORE any early return below. beforeDocumentChange
                    // stored one for THIS change; bailing out without removing it would leave a
                    // stale range in the map keyed by a live Document.
                    val range = pending.remove(event.document) ?: return
                    // Reload-from-disk guard (recorder PRD §4.5), the same discriminator
                    // ExternalChangeCoordinator's expected-model feeder uses. When an external
                    // tool rewrites a file IntelliJ has open with a CLEAN buffer — `git pull`,
                    // `git stash pop`, a CLI agent — the platform silently replaces the buffer to
                    // match disk. That replacement is a real DocumentEvent, and it is one big
                    // delta, so PasteClassifier (any single insert >= PASTE_MIN_INSERT_CHARS)
                    // called it a paste: the recorder logged a PARTNER'S PULLED WORK as code this
                    // student pasted, verbatim, while fs.external_change recorded the very same
                    // change correctly a millisecond later. On a shared group repo that is a
                    // false-accusation generator, so the paste/doc.change path must not see it.
                    //
                    // The discriminator: a reload converges the buffer to disk and leaves the
                    // document SAVED; only a genuine in-editor edit leaves it UNSAVED. IntelliJ
                    // makes this exact, unlike VS Code — FileDocumentManagerImpl.documentChanged
                    // keys off `hasWriteAction(ExternalChangeAction)`, so it has already added the
                    // document to (or removed it from) the unsaved set by the time this listener
                    // runs. There is no "the dirty flag flips one event later" window here, which
                    // is why doc-wiring.ts needs a synchronous disk read and this does not; the
                    // first-edit-after-save cases are pinned by DocWiringReloadTest.
                    //
                    // The change is NOT lost: DocumentReloadExternalChangeListener (path 3) and
                    // the VFS listener (path 2) emit fs.external_change for it. Accepted gap: a
                    // file outside `files_under_review` has no such fallback, so its reload goes
                    // unrecorded. An unrecorded reload beats a fabricated paste.
                    if (!fdm.isDocumentUnsaved(event.document)) return
                    val delta = buildDocChangeDelta(
                        range.start.line, range.start.character,
                        range.end.line, range.end.character,
                        event.newFragment.toString(),
                    )
                    val path = relativePath(vf, sink.workspaceRoot)
                    val correlator = sink.pasteCorrelator
                    if (correlator == null) {
                        sink.onDocChange(buildDocChangePayload(path, delta))
                        return
                    }
                    when (val decision = correlator.onDocChange(listOf(delta))) {
                        is PasteDecision.EmitPaste -> sink.onPaste(decision.fields.toPastePayload(path, decision.range))
                        is PasteDecision.EmitDocChange -> sink.onDocChange(buildDocChangePayload(path, delta, decision.source))
                    }
                }
            },
            parentDisposable,
        )

        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val sink = sinkFor(file) ?: return
                    emitDocOpenFor(file, sink)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val sink = sinkFor(file) ?: return
                    sink.onDocClose(buildDocClosePayload(relativePath(file, sink.workspaceRoot)))
                }
            },
        )

        // doc.save (recorder PRD §4.2) is driven by the POST-WRITE VFS signal, never by
        // FileDocumentManagerListener.beforeDocumentSaving.
        //
        // beforeDocumentSaving fires before the physical write, and IntelliJ mutates the
        // document from inside that very callback — the platform's TrailingSpacesStripper is
        // itself a beforeDocumentSaving listener, as is the rest of Actions-on-Save. Whichever
        // listener runs first sees a different document than the one that reaches disk, so a
        // hash taken there records content that never existed on disk. That is not theoretical:
        // it shipped, and a real submission's doc.save hash was the submitted file plus the four
        // spaces the stripper had just removed, which the analyzer correctly reported as "file
        // was changed outside the recording" against a student who had done nothing of the sort.
        // There is no afterDocumentSaving, so the only true post-write signal the platform
        // offers is the VFS content-change event with isFromSave() == true — the same signal
        // SaveTimeExternalChangeChecker's docstring already named as the one to use.
        //
        // The reference implementation lands in the same place from the other direction:
        // doc-wiring.ts hashes what `readFile` returns, "because the VS Code buffer may differ
        // from what a concurrent tool wrote", and always emits doc.save with the on-disk hash.
        //
        // Why this listener and not the per-session one in ExternalChangeCoordinator: doc.*
        // ownership is the router's nearest-ancestor rule, so exactly one session records a
        // save even under nested manifest roots. A per-session VFS listener filtered only by
        // "is this under my root" would emit twice for a nested root's file.
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Runs on the EDT inside a write action: triage only (resolve owner +
                    // relative path), and hand the content read to [vfsDispatch]. Routing stays
                    // on the EDT exactly as it does for document events.
                    val saved = ArrayList<SavedFile>()
                    for (e in events) {
                        if (e !is VFileContentChangeEvent || !e.isFromSave) continue
                        val vf = e.file
                        val sink = sinkFor(vf) ?: continue
                        saved.add(SavedFile(vf, relativePath(vf, sink.workspaceRoot), sink))
                    }
                    if (saved.isEmpty()) return
                    vfsDispatch {
                        for (s in saved) {
                            val onDisk = runCatching { readSavedText(s.file) }
                                .onFailure { LOG.warn("could not read ${s.relativePath} after save; doc.save dropped", it) }
                                .getOrNull() ?: continue
                            s.sink.onSaveObserved(s.relativePath, onDisk)
                        }
                    }
                }
            },
        )

        // Catch-up: files already open when wiring starts never fire fileOpened.
        catchUpOpenFiles()
    }

    /**
     * Emit doc.open for every currently-open file that some session now owns. Run once at
     * construction, and again by RecorderSessionManager on EVERY session start — because this
     * project-scoped wiring is constructed only once (on the first session), a later session
     * whose root already has files open would otherwise never see their doc.open baseline. The
     * [seenPaths] de-dup (keyed by absolute path) makes repeated calls idempotent: a file already
     * caught up is not re-emitted, only the newly-owned root's open files are.
     *
     * Runs on the EDT (inline when the caller is already there, so the init-time call above does
     * not hop twice). Two reasons, both load-bearing: `getOpenFiles()` walks `EditorsSplitters`,
     * which is EDT-owned Swing state — off the EDT `getSplitters()` silently falls back to the
     * main splitters and returns a possibly-wrong list, with no assertion to tell you — and the
     * enumeration must be atomic with respect to write actions so no doc.change can be logged for
     * a file whose doc.open baseline has not been emitted yet.
     */
    fun catchUpOpenFiles() = runOnEdtAndWait {
        for (vf in FileEditorManager.getInstance(project).openFiles) {
            val sink = sinkFor(vf) ?: continue
            emitDocOpenFor(vf, sink)
        }
    }

    private fun sinkFor(vf: VirtualFile): RecordableSessionSink? {
        if (!localFsOf(vf)) return null
        val path = nioPathOf(vf) ?: return null
        return router.sinkFor(path)
    }

    /**
     * [document], when non-null, is a document the caller already holds and is already guaranteed
     * a stable, correct snapshot of — the `beforeDocumentChange` caller, which runs on the EDT
     * inside the write action that is about to mutate it. That caller must NOT go through
     * [runReadActionBlocking]: it needs the exact pre-change text (a re-resolve is pointless), it
     * already has read access, and taking a nested cancellable read action inside a write action
     * is a needless hazard. Every other caller passes null and re-resolves under a read action.
     */
    private fun emitDocOpenFor(vf: VirtualFile, sink: RecordableSessionSink, document: Document? = null) {
        val path = nioPathOf(vf) ?: return
        if (!seenPaths.add(path)) return // defensive de-dup, keyed by absolute path
        // Model access under a read action. fileOpened arrives on the EDT, but catchUpOpenFiles()
        // is driven from RecorderSessionManager's activation coroutine (a background dispatcher):
        // getDocument() asserts read access there, and text/lineCount must come from ONE snapshot
        // or a concurrent write action tears the doc.open baseline. Hashing + emission are pure/IO
        // and deliberately stay outside the lock.
        val snapshot = if (document != null) {
            document.text to document.lineCount
        } else {
            runReadActionBlocking {
                val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@runReadActionBlocking null
                doc.text to doc.lineCount
            }
        } ?: return
        val (text, lineCount) = snapshot
        sink.onDocOpen(buildDocOpenPayload(relativePath(vf, sink.workspaceRoot), Sha256.hex(text), lineCount.toLong(), text))
    }

    private fun relativePath(vf: VirtualFile, workspaceRoot: Path): String {
        val nio = nioPathOf(vf) ?: return vf.name
        return runCatching { workspaceRoot.normalize().relativize(nio.normalize()).toString().replace('\\', '/') }
            .getOrDefault(vf.name)
    }

    private fun rangeOf(document: Document, offset: Int, length: Int): Range {
        val startLine = document.getLineNumber(offset)
        val startChar = offset - document.getLineStartOffset(startLine)
        val endOffset = offset + length
        val endLine = document.getLineNumber(endOffset)
        val endChar = endOffset - document.getLineStartOffset(endLine)
        return Range(Position(startLine.toLong(), startChar.toLong()), Position(endLine.toLong(), endChar.toLong()))
    }

    private companion object {
        private val LOG = Logger.getInstance(DocWiring::class.java)
    }
}
