package dev.provenance.core

/**
 * Pure buffer-flush decision function (recorder PRD §4.7: "flush to disk every 1 s
 * or 256 KB, whichever comes first"). No state, no I/O — a pure decision from inputs.
 * Direct port of log-core's buffer-policy.ts.
 */

data class BufferPolicyConfig(
    /** Flush when buffered bytes reach or exceed this. Default: 256 KiB. */
    val maxBytes: Int = 256 * 1024,
    /** Flush when ms since last flush reaches or exceeds this. Default: 1000. */
    val maxIntervalMs: Long = 1000,
)

data class BufferPolicyInput(
    val bufferedBytes: Int,
    /** Monotonic timestamp (ms) of the last flush. */
    val lastFlushAtMs: Long,
    /** Current monotonic timestamp (ms). */
    val nowMs: Long,
)

val DEFAULT_BUFFER_POLICY: BufferPolicyConfig = BufferPolicyConfig()

/**
 * Returns true if the buffer should be flushed now.
 *  - bufferedBytes == 0 → never flush (no point writing an empty buffer).
 *  - bufferedBytes >= maxBytes → flush (size threshold).
 *  - (nowMs - lastFlushAtMs) >= maxIntervalMs → flush (time threshold).
 */
fun shouldFlush(input: BufferPolicyInput, config: BufferPolicyConfig = DEFAULT_BUFFER_POLICY): Boolean {
    if (input.bufferedBytes == 0) return false
    if (input.bufferedBytes >= config.maxBytes) return true
    if (input.nowMs - input.lastFlushAtMs >= config.maxIntervalMs) return true
    return false
}
