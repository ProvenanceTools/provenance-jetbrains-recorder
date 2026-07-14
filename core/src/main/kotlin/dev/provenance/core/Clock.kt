package dev.provenance.core

import java.time.Instant

/**
 * Clock abstraction for testable, deterministic time handling.
 * CLAUDE.md: "Use a monotonic clock for `t`. Use wall clock for `wall`. Don't conflate."
 * Mirrors log-core's clock.ts.
 */
interface Clock {
    /** Monotonic millisecond timestamp with no defined epoch (suitable for `t`). */
    fun now(): Long

    /** Current wall time as an ISO 8601 UTC string (suitable for `wall`). */
    fun wall(): String
}

/**
 * Production clock. now() uses System.nanoTime()/1_000_000 — the JVM's monotonic
 * clock (the analogue of performance.now()); do NOT use currentTimeMillis() here,
 * it is wall-clock and can jump. wall() = Instant.now().toString() (ISO-8601 UTC).
 */
class SystemClock : Clock {
    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun wall(): String = Instant.now().toString()
}

/**
 * Deterministic clock for tests. Starts at a fixed monotonic value and wall time;
 * advance(ms) moves both forward. Mirrors log-core's FixedClock.
 */
class FixedClock(
    initialNowMs: Long = 0,
    initialWall: Instant = Instant.EPOCH,
) : Clock {
    private var nowMs: Long = initialNowMs
    private var wallInstant: Instant = initialWall

    override fun now(): Long = nowMs

    override fun wall(): String = wallInstant.toString()

    /** Advance both the monotonic clock and the wall clock by [ms] milliseconds. */
    fun advance(ms: Long) {
        nowMs += ms
        wallInstant = wallInstant.plusMillis(ms)
    }

    /** Directly set the monotonic value (for simulating non-wall jumps). */
    fun setNow(value: Long) {
        nowMs = value
    }

    /** Directly set the wall time. */
    fun setWall(instant: Instant) {
        wallInstant = instant
    }
}
