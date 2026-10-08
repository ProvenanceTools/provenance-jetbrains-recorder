package dev.provenance.recorder.session

/**
 * Schedules [task] once after [delayMs] and returns a cancel handle. A seam so the debounce
 * is testable without real time; production wraps the IDE's app scheduled executor.
 */
fun interface DebounceScheduler {
    fun schedule(delayMs: Long, task: Runnable): () -> Unit
}

/**
 * Trailing-edge debounce for the ROLLING SEAL after a `doc.save`.
 *
 * Checkpoint rolls only happen every N entries, but a student routinely saves and then
 * `git add` / `git commit`s with the IDE still open. Without a roll after the last save, the
 * committed seal records file hashes from before it. [request] (called once the `doc.save` is
 * chained and handed to the writer) arms a timer; each further request restarts it, so a burst
 * of saves costs one roll, [delayMs] after the last one.
 *
 * Shape guarantees:
 *  - at most one [roll] in flight; a request that arrives while one runs is remembered and
 *    produces exactly one follow-up roll (latest wins), never a pile of them;
 *  - after [dispose], nothing is scheduled and a timer that already fired does nothing.
 *
 * [roll] must flush the writer before sealing (so the seal's `.slog` digest covers the save)
 * and must itself refuse non-final rolls once the final roll is written: [dispose] cannot
 * recall a roll that is already running, only prevent new ones.
 */
class SealRollDebouncer(
    private val delayMs: Long,
    private val scheduler: DebounceScheduler,
    private val roll: () -> Unit,
) {
    private val lock = Any()
    private var disposed = false
    private var cancelPending: (() -> Unit)? = null
    private var running = false
    private var dirty = false

    fun request() {
        synchronized(lock) {
            if (disposed) return
            if (running) {
                dirty = true
                return
            }
            cancelPending?.invoke()
            cancelPending = scheduler.schedule(delayMs, ::fire)
        }
    }

    private fun fire() {
        synchronized(lock) {
            if (disposed || running) return
            cancelPending = null
            running = true
            dirty = false
        }
        try {
            roll()
        } finally {
            synchronized(lock) {
                running = false
                if (dirty && !disposed) {
                    dirty = false
                    cancelPending = scheduler.schedule(delayMs, ::fire)
                }
            }
        }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            cancelPending?.invoke()
            cancelPending = null
        }
    }
}
