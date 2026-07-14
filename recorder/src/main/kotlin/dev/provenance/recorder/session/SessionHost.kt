package dev.provenance.recorder.session

import dev.provenance.core.Clock
import dev.provenance.core.Envelope
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.chainEntry
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * SessionHost — owns the running session's chain state (seq, prevHash, tStart) and
 * emits chained log entries synchronously. Mirrors session-host.ts.
 * CLAUDE.md: "Log writes are ordered." No async in emit.
 */
interface SessionHost {
    /** Build the envelope, chain it, call onEntry, return the chained entry. */
    fun emit(kind: String, data: JsonObject): HashedEnvelope

    val sessionId: String

    /** Current sequence number (increments after each emit). */
    val seq: Long

    /** Monotonic clock value captured at session start. */
    val tStartMs: Long
}

/**
 * Create a SessionHost. Synchronous: emit() builds the Envelope, chains it (computing
 * the hash), advances seq/prevHash BEFORE calling onEntry (so state stays consistent
 * even if onEntry throws), then calls onEntry and returns the HashedEnvelope.
 *
 * seq starts at 0. prevHash starts at GENESIS_PREV_HASH. tStart = clock.now() at creation.
 */
fun createSessionHost(sessionId: String, clock: Clock, onEntry: (HashedEnvelope) -> Unit): SessionHost {
    return object : SessionHost {
        private var currentSeq = 0L
        private var prevHash = GENESIS_PREV_HASH
        private val tStart = clock.now()

        override val sessionId: String = sessionId
        override val seq: Long get() = currentSeq
        override val tStartMs: Long get() = tStart

        override fun emit(kind: String, data: JsonObject): HashedEnvelope {
            val seq = currentSeq
            // t: ms elapsed since session start (monotonic). Non-negative; floor at 0.
            val t = max(0L, (clock.now() - tStart).toDouble().roundToLong())
            val wall = clock.wall()

            val entry = chainEntry(prevHash, Envelope(seq = seq, t = t, wall = wall, kind = kind, data = data))

            // Advance state before onEntry to maintain consistency even if onEntry throws.
            currentSeq = seq + 1
            prevHash = entry.hash

            onEntry(entry)
            return entry
        }
    }
}
