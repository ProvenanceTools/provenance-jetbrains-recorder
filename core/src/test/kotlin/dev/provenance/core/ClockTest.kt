package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ClockTest {
    private val iso8601Utc = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")

    @Test
    fun `FixedClock advance moves both now and wall together`() {
        val clock = FixedClock(initialNowMs = 100, initialWall = Instant.parse("2026-07-14T00:00:00Z"))
        assertEquals(100L, clock.now())
        assertEquals("2026-07-14T00:00:00Z", clock.wall())
        clock.advance(2500)
        assertEquals(2600L, clock.now())
        assertEquals("2026-07-14T00:00:02.500Z", clock.wall())
    }

    @Test
    fun `FixedClock setNow moves only the monotonic value`() {
        val clock = FixedClock(initialNowMs = 0, initialWall = Instant.parse("2026-07-14T00:00:00Z"))
        clock.setNow(9999)
        assertEquals(9999L, clock.now())
        // Wall clock unchanged — simulates a monotonic jump without a wall jump.
        assertEquals("2026-07-14T00:00:00Z", clock.wall())
    }

    @Test
    fun `FixedClock setWall moves only the wall value`() {
        val clock = FixedClock(initialNowMs = 5, initialWall = Instant.EPOCH)
        clock.setWall(Instant.parse("2026-01-01T12:00:00Z"))
        assertEquals(5L, clock.now())
        assertEquals("2026-01-01T12:00:00Z", clock.wall())
    }

    @Test
    fun `SystemClock wall matches ISO-8601 UTC shape`() {
        val wall = SystemClock().wall()
        assertTrue(iso8601Utc.matches(wall), "wall was: $wall")
    }

    @Test
    fun `SystemClock now is monotonic non-decreasing`() {
        val clock = SystemClock()
        val a = clock.now()
        val b = clock.now()
        assertTrue(b >= a)
    }
}
