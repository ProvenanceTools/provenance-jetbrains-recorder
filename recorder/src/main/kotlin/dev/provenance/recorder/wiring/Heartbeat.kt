package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.Clock
import dev.provenance.core.SessionHeartbeatPayload
import dev.provenance.recorder.io.FlushScheduler
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Heartbeat emitter (recorder PRD §4.2: session.heartbeat every 30s while the IDE is
 * open — window focused (bool), active file, idle since (ms)). Mirrors heartbeat.ts.
 *
 * The core is injectable (scheduler, clock, focus + active-file providers) so it is a
 * plain unit test, not a platform test. [recordActivity] resets the idle timer and is
 * called by the platform subscriptions wired in [start] (app (de)activation, active-
 * editor change, document change). Focus is read LIVE at tick time (not cached at
 * subscription time), matching the TS heartbeat's explicit rule.
 */
class Heartbeat(
    private val emit: (SessionHeartbeatPayload) -> Unit,
    private val clock: Clock,
    private val focusedProvider: () -> Boolean,
    private val getActiveFile: () -> String?,
    intervalMs: Long,
    scheduler: FlushScheduler,
) {
    private var lastActivityAtMs = clock.now()

    @Volatile
    private var disposed = false
    private val future: ScheduledFuture<*> = scheduler.scheduleAtFixedRate(intervalMs) { if (!disposed) tick() }

    /** Reset the idle timer. Called on any observed activity signal. */
    fun recordActivity() {
        lastActivityAtMs = clock.now()
    }

    /** One heartbeat tick — reads focus live and emits the payload. */
    fun tick() {
        emit(SessionHeartbeatPayload(focusedProvider(), getActiveFile(), clock.now() - lastActivityAtMs))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        future.cancel(false)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 30_000

        /**
         * Production factory: schedules ticks on AppExecutorUtil and wires the activity
         * signals (app (de)activation, active-editor/document changes) to recordActivity(),
         * all tied to [parentDisposable]. Not exercised by unit tests (needs a live
         * Application/message bus); the tick/idle logic is covered via the injectable core.
         */
        fun start(
            emit: (SessionHeartbeatPayload) -> Unit,
            clock: Clock,
            getActiveFile: () -> String?,
            parentDisposable: Disposable,
            intervalMs: Long = DEFAULT_INTERVAL_MS,
        ): Heartbeat {
            // Focus flag toggled by the app activation listener; read live each tick.
            val focused = java.util.concurrent.atomic.AtomicBoolean(true)
            val scheduler = FlushScheduler { periodMs, task ->
                AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
            }
            val heartbeat = Heartbeat(emit, clock, { focused.get() }, getActiveFile, intervalMs, scheduler)

            val appBus = ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            appBus.subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) {
                        focused.set(true)
                        heartbeat.recordActivity()
                    }

                    override fun applicationDeactivated(ideFrame: IdeFrame) {
                        focused.set(false)
                    }
                },
            )

            EditorFactory.getInstance().eventMulticaster.addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) = heartbeat.recordActivity()
                },
                parentDisposable,
            )

            return heartbeat
        }
    }
}
