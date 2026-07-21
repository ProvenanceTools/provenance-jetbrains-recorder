package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
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

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (sinkFor(vf) == null) return
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

    private fun emitDocOpenFor(vf: VirtualFile, sink: RecordableSessionSink) {
        val path = nioPathOf(vf) ?: return
        if (!seenPaths.add(path)) return // defensive de-dup, keyed by absolute path
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val text = doc.text
        sink.onDocOpen(buildDocOpenPayload(relativePath(vf, sink.workspaceRoot), Sha256.hex(text), doc.lineCount.toLong(), text))
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
