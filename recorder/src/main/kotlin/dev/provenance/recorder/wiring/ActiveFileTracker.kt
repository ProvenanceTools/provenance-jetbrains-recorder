package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/**
 * Lock-free cache of the currently selected editor's file name, for
 * `session.heartbeat.active_file` (PRD §4.2).
 *
 * The heartbeat ticks from a [java.util.concurrent.ScheduledExecutorService] — a background
 * thread — every 30s for the whole life of the session. Reading
 * `FileEditorManager.selectedFiles` there walks EDT-owned editor/tab state
 * (EditorsSplitters → EditorWindow → the selected-composite StateFlow), so the *push* direction
 * is the right one: the platform already delivers [FileEditorManagerListener.selectionChanged]
 * on the EDT, so the name is cached there and each tick just reads an [AtomicReference].
 * Blocking the scheduler thread on the read lock every tick would be the wrong trade — the
 * heartbeat must never contend with a write action.
 *
 * **Seeding.** `selectionChanged` only fires on a *future* tab switch, and a session starts on
 * a workspace whose files are typically already open, so without a seed `active_file` would
 * read `null` until the student switched tabs. The seed reads the platform exactly once, on the
 * EDT (directly if already there, otherwise via `invokeLater`, ordered behind any
 * `selectionChanged` that lands first).
 *
 * Lifecycle: the subscription is a child of [parentDisposable] (the session Disposable), so it
 * is torn down with the session.
 */
class ActiveFileTracker(
    project: Project,
    parentDisposable: Disposable,
    /** Test seam for the one platform read the seed performs. */
    readSelectedFileName: () -> String? = {
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.name
    },
    /** Test seam for "run this on the EDT". */
    onEdt: (Runnable) -> Unit = { r ->
        ApplicationManager.getApplication().invokeLater(r, ModalityState.any(), project.disposed)
    },
) {
    private val current = AtomicReference<String?>(null)

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    current.set(event.newFile?.name)
                }
            },
        )
        val seed = Runnable { current.set(readSelectedFileName()) }
        if (ApplicationManager.getApplication().isDispatchThread) seed.run() else onEdt(seed)
    }

    /** The selected editor's file name, or null. Safe (and cheap) from any thread. */
    fun activeFileName(): String? = current.get()
}
