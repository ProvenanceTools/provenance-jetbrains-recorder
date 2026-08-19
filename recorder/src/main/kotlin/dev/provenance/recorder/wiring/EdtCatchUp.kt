package dev.provenance.recorder.wiring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * Run [block] on the EDT and block the caller until it finishes — or run it inline when the
 * caller already *is* the EDT.
 *
 * **Why the startup catch-up needs this (recorder PRD §4.2.1).** The format guarantees that an
 * already-open file's `doc.open` — which carries the replay baseline — precedes every
 * `doc.change` for that file, and follows `session.start`. The VS Code recorder gets that from
 * its host being single-threaded: `doc-wiring.ts` registers its subscriptions and then walks
 * `vscode.workspace.textDocuments` in the SAME synchronous turn, so no `onDidChangeTextDocument`
 * callback can land in between.
 *
 * IntelliJ's equivalent of "one uninterruptible turn" is the EDT. A document can only be mutated
 * inside a write action, and a write action can only run on the EDT — so listener registration,
 * the open-file enumeration, the document snapshot, and the `doc.open` emission are atomic with
 * respect to document changes exactly when they all happen inside one EDT runnable. Split them,
 * and a keystroke can be logged as a `doc.change` before the file's `doc.open` exists (replay
 * then has no baseline) *and* be folded into the baseline that `doc.open` later reads (replay
 * then applies the same delta twice).
 *
 * It is also the only correct way to touch `FileEditorManager.getOpenFiles()`: it walks
 * `EditorsSplitters`, which is EDT-owned Swing state, and off the EDT `getSplitters()` silently
 * falls back to the main splitters — no assertion fires, the list is just possibly wrong.
 *
 * **Deadlock.** Blocking a background thread on the EDT is safe only when the caller holds no
 * read/write lock and the EDT is not itself waiting on that caller. Both hold for the one
 * production caller: `RecorderActivationActivity` is a `ProjectActivity` coroutine on a
 * background dispatcher, launched (not awaited) by the platform, and holds no application lock.
 * [ModalityState.any] is deliberate — with the default modality the catch-up would be starved
 * behind any modal dialog that happens to be up at project open, stalling activation for as long
 * as the student leaves it there. `any()` is safe here because the block only reads the platform
 * model and appends to the log; it starts no write action and modifies no model state. Callers
 * that already hold a read action must NOT use this.
 *
 * **Why this is spelled out rather than delegated.** This used to call
 * `com.intellij.openapi.application.invokeAndWaitIfNeeded`, which is `@ApiStatus.Internal` and
 * therefore rejected by JetBrains Marketplace plugin verification. The body below is that
 * helper's exact logic against public API: it dispatches on [com.intellij.openapi.application.Application.isDispatchThread]
 * and otherwise calls [com.intellij.openapi.application.Application.invokeAndWait], which is
 * what the internal helper delegates to. The explicit EDT branch is not redundant with
 * `invokeAndWait`'s own on-EDT shortcut: `ApplicationImpl.doInvokeAndWait` runs an on-EDT
 * runnable through `runIntendedWriteActionOnCurrentThread`, i.e. under the write-intent lock.
 * Running [block] directly keeps the already-on-EDT path taking no lock the caller did not
 * already hold — the same behaviour this function has always had.
 */
fun runOnEdtAndWait(block: () -> Unit) {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
        block()
    } else {
        application.invokeAndWait({ block() }, ModalityState.any())
    }
}
