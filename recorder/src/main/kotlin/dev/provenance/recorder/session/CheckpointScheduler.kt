package dev.provenance.recorder.session

import dev.provenance.core.Checkpoint
import dev.provenance.core.signCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Signs and persists checkpoints off the synchronous append path, without ever letting two
 * sign+persist calls run out of order or interleaved. Ported from the `pendingCheckpoint`
 * promise-chain pattern in provenance/packages/recorder/src/extension.ts:250-282.
 *
 * CLAUDE.md: "No unordered concurrency over operations that must be ordered" / "every async
 * loop has a dispose() hook" — the Mutex here is the ordering guarantee (equivalent to TS's
 * promise chaining regardless of the injected scope's dispatcher parallelism); drain() is
 * the shutdown hook, awaited by the caller at session end.
 *
 * `privateKey32` is the session's raw in-memory private key (never persisted in this form —
 * only the encrypted form goes to disk, via core's encryptSessionPrivkey + MetaWriter.create).
 * signCheckpoint is synchronous CPU-bound ed25519 signing; the scope is what keeps it off
 * whatever thread calls schedule().
 */
class CheckpointScheduler(
    private val scope: CoroutineScope,
    private val privateKey32: ByteArray,
    private val appendCheckpoint: suspend (Checkpoint) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val mutex = Mutex()
    private var lastJob: Job? = null

    /** Enqueue a sign+persist for (seq, entryHash). Returns immediately. */
    fun schedule(seq: Long, entryHash: String) {
        lastJob = scope.launch {
            mutex.withLock {
                try {
                    val cp = signCheckpoint(seq, entryHash, privateKey32)
                    appendCheckpoint(cp)
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        }
    }

    /** Await the most recently scheduled sign+persist. Call at session end. */
    suspend fun drain() {
        lastJob?.join()
    }
}
