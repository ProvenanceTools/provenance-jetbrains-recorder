package dev.provenance.recorder.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.recorder.state.Delta
import dev.provenance.recorder.state.ExpectedContentRegistry
import java.nio.file.Path

/**
 * Unifies the three PRD §4.5 detection paths behind one emission point + lifecycle, and
 * feeds the expected-content model so the paths have a baseline to compare against.
 *
 * Reconciliation with Plan 4: in the VS Code recorder, doc-wiring.ts owns the
 * ExpectedContentRegistry and feeds it (getOrCreate on open, applyDeltas on change,
 * compare on save). Plan 4's IntelliJ DocWiring does NOT feed any model — it only emits
 * doc.* payloads. Rather than invasively rewrite committed Plan 4 code, this coordinator
 * installs its own lightweight "expected-model feeder" (a DocumentListener for offset
 * deltas + a fileOpened seed + open-file catch-up) that is the IntelliJ analogue of
 * doc-wiring.ts's registry feeding. Offset deltas come straight off DocumentEvent, so no
 * line/character↔offset reconversion is needed.
 *
 * Lifecycle: implements [Disposable]; register with the session Disposable via
 * Disposer.register(sessionDisposable, coordinator). Every message-bus connection is a
 * child of `this`, so dispose() tears them all down (CLAUDE.md: one shutdown path).
 */
class ExternalChangeCoordinator(
    private val project: Project,
    private val workspaceRoot: Path,
    filesUnderReview: List<String>,
    private val emit: (FsExternalChangePayload) -> Unit,
    private val isRecentEditorChange: (String) -> Boolean = { false },
    private val vfsDispatch: (() -> Unit) -> Unit = VfsExternalChangeListener.DEFAULT_DISPATCH,
) : Disposable {
    val registry = ExpectedContentRegistry(filesUnderReview)
    private val engine = ExternalChangeEngine(registry)
    private val saveChecker = SaveTimeExternalChangeChecker(engine, emit)

    /** Register all listeners + seed already-open watched files. Call once post-activation. */
    fun start() {
        installExpectedModelFeeder()

        val vfsListener = VfsExternalChangeListener(
            workspaceRoot = workspaceRoot,
            engine = engine,
            saveChecker = saveChecker,
            emit = emit,
            isRecentEditorChange = isRecentEditorChange,
            dispatch = vfsDispatch,
        )
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, vfsListener)

        val reloadListener = DocumentReloadExternalChangeListener(workspaceRoot, engine, emit)
        project.messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, reloadListener)
    }

    /** Path 1 entry point for a true post-save hook, if a later plan adds one. */
    fun checkAfterSave(relativePath: String, file: VirtualFile) =
        saveChecker.checkAfterSave(relativePath, file)

    private fun installExpectedModelFeeder() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val fdm = FileDocumentManager.getInstance()
                    val vf = fdm.getFile(event.document) ?: return
                    val rel = relativePathOf(vf, workspaceRoot) ?: return
                    if (!registry.isWatched(rel)) return
                    // Only track once seeded at open — an untracked watched file's first
                    // signal is a create/reload, not a keystroke.
                    val ec = registry.get(rel) ?: return
                    // Skip disk-reload-driven document changes. A reload replaces the buffer
                    // to match disk, leaving the document SAVED (not unsaved), and fires its
                    // own fileContentReloaded (path 3). If the feeder applied those deltas it
                    // would silently sync the model to disk and mask the external change —
                    // the exact double-handling risk the plan flagged. Only genuine in-editor
                    // edits leave the document unsaved.
                    if (!fdm.isDocumentUnsaved(event.document)) return
                    ec.applyDelta(Delta(event.offset, event.oldLength, event.newFragment.toString()))
                }
            },
            this,
        )

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = seed(file)
            },
        )

        for (vf in FileEditorManager.getInstance(project).openFiles) seed(vf)
    }

    private fun seed(vf: VirtualFile) {
        val rel = relativePathOf(vf, workspaceRoot) ?: return
        if (!registry.isWatched(rel)) return
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return
        registry.getOrCreate(rel, doc.text)
    }

    override fun dispose() {
        // message-bus connections + the document listener are children of `this` — the
        // platform Disposer tears them down automatically. Nothing else to release.
    }
}
