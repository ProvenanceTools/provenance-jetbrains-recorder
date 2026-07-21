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
import dev.provenance.core.SessionResumedPayload
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
 *
 * **Suspend detection.** The IDE process is never cleanly deactivated when the OS suspends it
 * (lid close / sleep): the scheduler simply stops ticking, then resumes the already-overdue
 * tick on wake, with no `session.end` in between. Without a marker, that gap reads to the
 * analyzer's `gap_in_heartbeats` heuristic as a suspicious missing-recorder window. So each
 * [tick] compares WALL-clock time (via [getWallMs]) against the previous tick; if the gap is at
 * least twice [intervalMs], a [SessionResumedPayload] is emitted first, then the heartbeat, so
 * the marker's seq sits strictly between the two bounding heartbeat seqs (the analyzer suppresses
 * on seq ranges). This deliberately uses wall time, not [clock]'s monotonic reading: on macOS the
 * monotonic clock keeps advancing through sleep (so it can't see the gap), while on Linux it
 * doesn't — wall-vs-expected-tick-count is the only check that works on both. Do not switch this
 * to a monotonic comparison.
 */
class Heartbeat(
    private val emit: (SessionHeartbeatPayload) -> Unit,
    private val emitResumed: (SessionResumedPayload) -> Unit,
    private val clock: Clock,
    private val focusedProvider: () -> Boolean,
    private val getActiveFile: () -> String?,
    private val intervalMs: Long,
    scheduler: FlushScheduler,
    private val getWallMs: () -> Long,
) {
    private var lastActivityAtMs = clock.now()

    /** Wall-clock reading at the previous tick (or construction, for the very first tick). */
    private var lastTickWallMs = getWallMs()

    @Volatile
    private var disposed = false
    private val future: ScheduledFuture<*> = scheduler.scheduleAtFixedRate(intervalMs) { if (!disposed) tick() }

    /** Reset the idle timer. Called on any observed activity signal. */
    fun recordActivity() {
        lastActivityAtMs = clock.now()
    }

    /**
     * One heartbeat tick. Checks the wall-clock gap since the previous tick first — emitting
     * session.resumed ahead of session.heartbeat when it looks like a suspend — then reads focus
     * live and emits the heartbeat payload.
     */
    fun tick() {
        val nowWall = getWallMs()
        val gapMs = nowWall - lastTickWallMs
        // gapMs < 0 is a backwards wall-clock correction (NTP), not a suspend — never emit for it.
        if (gapMs >= 2 * intervalMs) {
            emitResumed(SessionResumedPayload(gapMs = gapMs, expectedIntervalMs = intervalMs))
        }
        lastTickWallMs = nowWall
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
            emitResumed: (SessionResumedPayload) -> Unit = {},
            getWallMs: () -> Long = System::currentTimeMillis,
        ): Heartbeat {
            // Focus flag toggled by the app activation listener; read live each tick.
            val focused = java.util.concurrent.atomic.AtomicBoolean(true)
            val scheduler = FlushScheduler { periodMs, task ->
                AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
            }
            val heartbeat =
                Heartbeat(emit, emitResumed, clock, { focused.get() }, getActiveFile, intervalMs, scheduler, getWallMs)

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
