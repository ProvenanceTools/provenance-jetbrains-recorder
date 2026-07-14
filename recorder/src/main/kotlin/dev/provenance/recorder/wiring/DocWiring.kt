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
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.buildDocChangeDelta
import dev.provenance.recorder.events.buildDocChangePayload
import dev.provenance.recorder.events.buildDocClosePayload
import dev.provenance.recorder.events.buildDocOpenPayload
import dev.provenance.recorder.events.buildDocSavePayload
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * doc.open/change/save/close wiring (recorder PRD §4.2). Registers a global
 * DocumentListener (via the EditorFactory multicaster), a FileEditorManagerListener
 * for open/close, and a FileDocumentManagerListener for saves — all tied to
 * [parentDisposable] so everything tears down together. Mirrors doc-wiring.ts.
 *
 * Design decisions (per CLAUDE.md, made explicit not buried):
 *  1. One delta per event: IntelliJ's DocumentListener fires one before/after pair
 *     per atomic single-range mutation, so each doc.change carries a single-element
 *     deltas list (schema-valid; length 1). Multi-caret edits fire multiple pairs.
 *  2. Pre-change coordinates: offsets are converted to {line,character} in
 *     beforeDocumentChange, against the PRE-mutation document. Converting in
 *     documentChanged would resolve against the already-mutated doc → wrong range.
 *     The inserted text (newFragment) is read in documentChanged (valid there).
 *  3. Per-Document pending storage: the multicaster is global (every doc, every
 *     project), so pending ranges live in a WeakHashMap keyed by Document — a single
 *     field would be clobbered by interleaved events from unrelated documents.
 *
 * [localFsOf]/[nioPathOf] are injectable so the delta/coordinate logic is testable
 * in a light fixture (whose files are not on the local FS); production uses the real
 * VirtualFile checks.
 */
class DocWiring(
    private val project: Project,
    private val provenanceDir: Path,
    private val workspaceRoot: Path,
    private val emitDocOpen: (DocOpenPayload) -> Unit,
    private val emitDocChange: (DocChangePayload) -> Unit,
    private val emitDocSave: (DocSavePayload) -> Unit,
    private val emitDocClose: (DocClosePayload) -> Unit,
    parentDisposable: Disposable,
    private val manifestNames: Set<String> = MANIFEST_FILE_NAMES_FILTER,
    private val localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    private val nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
) {
    private val pending = WeakHashMap<Document, Range>()
    private val seenPaths = mutableSetOf<String>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isRecordable(vf)) return
                    pending[event.document] = rangeOf(event.document, event.offset, event.oldLength)
                }

                override fun documentChanged(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isRecordable(vf)) return
                    val range = pending.remove(event.document) ?: return
                    val delta = buildDocChangeDelta(
                        range.start.line, range.start.character,
                        range.end.line, range.end.character,
                        event.newFragment.toString(),
                    )
                    emitDocChange(buildDocChangePayload(relativePath(vf), delta))
                }
            },
            parentDisposable,
        )

        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (isRecordable(file)) emitDocOpenFor(file)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (isRecordable(file)) emitDocClose(buildDocClosePayload(relativePath(file)))
                }
            },
        )

        // beforeDocumentSaving fires with the about-to-be-written content already in
        // the Document, so sha256(document.text) here IS the content that lands on disk.
        // FileDocumentManagerListener.TOPIC is the current topic (AppTopics.FILE_DOCUMENT_SYNC
        // is deprecated).
        project.messageBus.connect(parentDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (!isRecordable(vf)) return
                    emitDocSave(buildDocSavePayload(relativePath(vf), Sha256.hex(document.text)))
                }
            },
        )

        // Catch-up: files already open when wiring starts never fire fileOpened.
        for (vf in FileEditorManager.getInstance(project).openFiles) {
            if (isRecordable(vf)) emitDocOpenFor(vf)
        }
    }

    private fun emitDocOpenFor(vf: VirtualFile) {
        val path = relativePath(vf)
        if (!seenPaths.add(path)) return // defensive de-dup (mirrors seenDocs in doc-wiring.ts)
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val text = doc.text
        emitDocOpen(buildDocOpenPayload(path, Sha256.hex(text), doc.lineCount.toLong(), text))
    }

    private fun isRecordable(vf: VirtualFile): Boolean =
        isRecordablePath(nioPathOf(vf), localFsOf(vf), workspaceRoot, provenanceDir, manifestNames)

    private fun relativePath(vf: VirtualFile): String {
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
