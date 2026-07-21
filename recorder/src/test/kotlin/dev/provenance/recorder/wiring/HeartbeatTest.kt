package dev.provenance.recorder.wiring

import dev.provenance.core.FixedClock
import dev.provenance.core.SessionHeartbeatPayload
import dev.provenance.core.SessionResumedPayload
import dev.provenance.recorder.io.FlushScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledFuture

private class ManualScheduler : FlushScheduler {
    var task: Runnable? = null
    var canceled = false
    override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> {
        this.task = task
        return object : ScheduledFuture<Any?> {
            override fun cancel(m: Boolean): Boolean { canceled = true; return true }
            override fun isCancelled() = canceled
            override fun isDone() = false
            override fun get(): Any? = null
            override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
            override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
            override fun compareTo(o: java.util.concurrent.Delayed?) = 0
        }
    }
    fun tick() = task?.run()
}

class HeartbeatTest {
    /**
     * Existing (pre-suspend-detection) tests don't exercise wall-clock gap behavior, so their
     * wall clock is a fixed constant: it never advances, so gapMs is always 0 and session.resumed
     * never fires.
     */
    private fun make(focused: () -> Boolean = { true }, activeFile: () -> String? = { "hw.py" }): Triple<FixedClock, ManualScheduler, MutableList<SessionHeartbeatPayload>> {
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val emitted = mutableListOf<SessionHeartbeatPayload>()
        Heartbeat({ emitted.add(it) }, {}, clock, focused, activeFile, 10, sched, { 0L })
        return Triple(clock, sched, emitted)
    }

    @Test
    fun `tick reports idle growing when no activity resets it`() {
        val (clock, sched, emitted) = make()
        clock.advance(10)
        sched.tick()
        assertEquals(1, emitted.size)
        assertEquals(10L, emitted[0].idleSinceMs)
        clock.advance(25)
        sched.tick()
        assertEquals(35L, emitted[1].idleSinceMs)
    }

    @Test
    fun `activity reset brings idle back near zero`() {
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val emitted = mutableListOf<SessionHeartbeatPayload>()
        val hb = Heartbeat({ emitted.add(it) }, {}, clock, { true }, { "hw.py" }, 10, sched, { 0L })
        clock.advance(8)
        hb.recordActivity() // reset at t=8
        clock.advance(2)
        sched.tick() // t=10, idle = 10-8 = 2
        assertEquals(2L, emitted[0].idleSinceMs)
    }

    @Test
    fun `focus and active file are read live at tick time`() {
        var focused = false
        var file: String? = "a.py"
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val emitted = mutableListOf<SessionHeartbeatPayload>()
        Heartbeat({ emitted.add(it) }, {}, clock, { focused }, { file }, 10, sched, { 0L })
        clock.advance(10); sched.tick()
        assertEquals(false, emitted[0].focused)
        assertEquals("a.py", emitted[0].activeFile)
        focused = true; file = null
        clock.advance(10); sched.tick()
        assertEquals(true, emitted[1].focused)
        assertEquals(null, emitted[1].activeFile)
    }

    @Test
    fun `dispose stops further ticks`() {
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val emitted = mutableListOf<SessionHeartbeatPayload>()
        val hb = Heartbeat({ emitted.add(it) }, {}, clock, { true }, { null }, 10, sched, { 0L })
        hb.dispose()
        assertTrue(sched.canceled)
        clock.advance(100)
        sched.tick() // guarded by disposed flag → no emit
        assertTrue(emitted.isEmpty())
    }

    // --- Suspend detection (session.resumed) ---
    // Wall clock is a mutable var here (unlike the constant used above) so each test can move it
    // forward by an arbitrary amount, independent of the monotonic FixedClock, simulating the OS
    // suspending the process between ticks.

    @Test
    fun `no session_resumed when the wall gap matches the normal interval`() {
        var wall = 0L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val resumed = mutableListOf<SessionResumedPayload>()
        Heartbeat({ }, { resumed.add(it) }, clock, { true }, { null }, 30_000, sched, { wall })
        wall += 30_000
        sched.tick()
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun `no session_resumed just below the 2x interval threshold`() {
        var wall = 0L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val resumed = mutableListOf<SessionResumedPayload>()
        Heartbeat({ }, { resumed.add(it) }, clock, { true }, { null }, 30_000, sched, { wall })
        wall += 59_999
        sched.tick()
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun `session_resumed fires at exactly 2x the interval with gap_ms and expected_interval_ms`() {
        var wall = 0L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val resumed = mutableListOf<SessionResumedPayload>()
        Heartbeat({ }, { resumed.add(it) }, clock, { true }, { null }, 30_000, sched, { wall })
        wall += 60_000 // exactly 2x — the spec's ">=" boundary
        sched.tick()
        assertEquals(1, resumed.size)
        assertEquals(60_000L, resumed[0].gapMs)
        assertEquals(30_000L, resumed[0].expectedIntervalMs)
    }

    @Test
    fun `session_resumed fires once per suspend, not on every subsequent tick`() {
        var wall = 0L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val resumed = mutableListOf<SessionResumedPayload>()
        Heartbeat({ }, { resumed.add(it) }, clock, { true }, { null }, 30_000, sched, { wall })
        wall += 5 * 60_000 // a long sleep
        sched.tick()
        assertEquals(1, resumed.size)
        wall += 30_000 // back to normal cadence
        sched.tick()
        assertEquals("no re-emit once cadence is back to normal", 1, resumed.size)
    }

    @Test
    fun `negative gap_ms from a backwards wall-clock correction never emits session_resumed`() {
        var wall = 1_000_000L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val resumed = mutableListOf<SessionResumedPayload>()
        Heartbeat({ }, { resumed.add(it) }, clock, { true }, { null }, 30_000, sched, { wall })
        wall -= 500_000 // NTP stepped the wall clock backwards
        sched.tick()
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun `session_resumed is emitted before the bounding heartbeat on the same tick`() {
        var wall = 0L
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val order = mutableListOf<String>()
        Heartbeat(
            { order.add("session.heartbeat") },
            { order.add("session.resumed") },
            clock,
            { true },
            { null },
            30_000,
            sched,
            { wall },
        )
        wall += 30_000
        sched.tick() // normal cadence, first heartbeat
        wall += 90_000 // suspend
        sched.tick()
        assertEquals(listOf("session.heartbeat", "session.resumed", "session.heartbeat"), order)
    }
}
