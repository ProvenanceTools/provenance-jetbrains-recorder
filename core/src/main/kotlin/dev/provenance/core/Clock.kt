package dev.provenance.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
 * Format an Instant EXACTLY like JavaScript's Date.toISOString(): always
 * `yyyy-MM-ddThh:mm:ss.SSSZ` with a fixed 3-digit millisecond field and a literal
 * 'Z'. This fixed width is load-bearing: the analyzer's monotonic-wall check
 * (PRD §5.4 check 6) compares `wall` strings LEXICOGRAPHICALLY, so a
 * variable-precision format (Instant.toString() drops millis when zero) would
 * sort "…00Z" after "…00.010Z" and read as a wall regression. Format parity with
 * log-core's `new Date().toISOString()` is required, not cosmetic.
 */
internal val ISO_MILLIS_UTC: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

internal fun formatWall(instant: Instant): String = ISO_MILLIS_UTC.format(instant)

/**
 * Production clock. now() uses System.nanoTime()/1_000_000 — the JVM's monotonic
 * clock (the analogue of performance.now()); do NOT use currentTimeMillis() here,
 * it is wall-clock and can jump. wall() = Instant.now().toString() (ISO-8601 UTC).
 */
class SystemClock : Clock {
    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun wall(): String = formatWall(Instant.now())
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

    override fun wall(): String = formatWall(wallInstant)

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
