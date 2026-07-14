package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ClockTest {
    // JS Date.toISOString() shape: ALWAYS a 3-digit millisecond field + literal Z.
    private val iso8601MillisUtc = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    @Test
    fun `FixedClock advance moves both now and wall together`() {
        val clock = FixedClock(initialNowMs = 100, initialWall = Instant.parse("2026-07-14T00:00:00Z"))
        assertEquals(100L, clock.now())
        // Fixed-width millis even at .000 — matches JS toISOString and keeps wall lexicographically sortable.
        assertEquals("2026-07-14T00:00:00.000Z", clock.wall())
        clock.advance(2500)
        assertEquals(2600L, clock.now())
        assertEquals("2026-07-14T00:00:02.500Z", clock.wall())
    }

    @Test
    fun `wall stays lexicographically monotonic across small advances`() {
        val clock = FixedClock(initialNowMs = 0, initialWall = Instant.parse("2026-07-14T00:00:00Z"))
        val w0 = clock.wall()
        clock.advance(10)
        val w1 = clock.wall()
        // The exact regression the analyzer's string-comparison check would flag.
        assertTrue(w0 <= w1, "$w0 should sort <= $w1")
        assertEquals("2026-07-14T00:00:00.000Z", w0)
        assertEquals("2026-07-14T00:00:00.010Z", w1)
    }

    @Test
    fun `FixedClock setNow moves only the monotonic value`() {
        val clock = FixedClock(initialNowMs = 0, initialWall = Instant.parse("2026-07-14T00:00:00Z"))
        clock.setNow(9999)
        assertEquals(9999L, clock.now())
        // Wall clock unchanged — simulates a monotonic jump without a wall jump.
        assertEquals("2026-07-14T00:00:00.000Z", clock.wall())
    }

    @Test
    fun `FixedClock setWall moves only the wall value`() {
        val clock = FixedClock(initialNowMs = 5, initialWall = Instant.EPOCH)
        clock.setWall(Instant.parse("2026-01-01T12:00:00Z"))
        assertEquals(5L, clock.now())
        assertEquals("2026-01-01T12:00:00.000Z", clock.wall())
    }

    @Test
    fun `SystemClock wall matches JS toISOString shape`() {
        val wall = SystemClock().wall()
        assertTrue(iso8601MillisUtc.matches(wall), "wall was: $wall")
    }

    @Test
    fun `SystemClock now is monotonic non-decreasing`() {
        val clock = SystemClock()
        val a = clock.now()
        val b = clock.now()
        assertTrue(b >= a)
    }
}
