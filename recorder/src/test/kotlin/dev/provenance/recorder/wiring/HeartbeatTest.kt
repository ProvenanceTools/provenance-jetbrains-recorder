package dev.provenance.recorder.wiring

import dev.provenance.core.FixedClock
import dev.provenance.core.SessionHeartbeatPayload
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
    private fun make(focused: () -> Boolean = { true }, activeFile: () -> String? = { "hw.py" }): Triple<FixedClock, ManualScheduler, MutableList<SessionHeartbeatPayload>> {
        val clock = FixedClock(0)
        val sched = ManualScheduler()
        val emitted = mutableListOf<SessionHeartbeatPayload>()
        Heartbeat({ emitted.add(it) }, clock, focused, activeFile, 10, sched)
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
        val hb = Heartbeat({ emitted.add(it) }, clock, { true }, { "hw.py" }, 10, sched)
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
        Heartbeat({ emitted.add(it) }, clock, { focused }, { file }, 10, sched)
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
        val hb = Heartbeat({ emitted.add(it) }, clock, { true }, { null }, 10, sched)
        hb.dispose()
        assertTrue(sched.canceled)
        clock.advance(100)
        sched.tick() // guarded by disposed flag → no emit
        assertTrue(emitted.isEmpty())
    }
}
