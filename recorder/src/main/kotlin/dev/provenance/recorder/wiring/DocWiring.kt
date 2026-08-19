package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.buildDocChangeDelta
import dev.provenance.recorder.events.buildDocChangePayload
import dev.provenance.recorder.events.buildDocClosePayload
import dev.provenance.recorder.events.buildDocOpenPayload
import dev.provenance.recorder.events.buildDocSavePayload
import dev.provenance.recorder.paste.PasteDecision
import dev.provenance.recorder.paste.toPastePayload
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * doc.open/change/save/close wiring (recorder PRD §4.2). Registered ONCE, project-scoped
 * (constructed by RecorderSessionManager, not per-session — see design.md's nested-manifest
 * discovery plan): a single global DocumentListener + FileEditorManagerListener +
 * FileDocumentManagerListener, each resolving the *one* owning session per event via
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
) {
    private val pending = WeakHashMap<Document, Range>()
    // Keyed by absolute nio path, NOT relative path: two different owning roots can each have
    // a file with the same relative name (e.g. "hw.py" under both cats/ and hog/), and a
    // relative-path key would wrongly treat the second as already-seen.
    private val seenPaths = mutableSetOf<Path>()

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
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    val sink = sinkFor(vf) ?: return
                    val range = pending.remove(event.document) ?: return
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

        project.messageBus.connect(parentDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    val sink = sinkFor(vf) ?: return
                    sink.onDocSave(buildDocSavePayload(relativePath(vf, sink.workspaceRoot), Sha256.hex(document.text)))
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
}
