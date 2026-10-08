package dev.provenance.recorder.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SealRollDebouncerTest {
    private val sched = ManualDebounceScheduler()
    private var rolls = 0

    private fun debouncer(roll: () -> Unit = { rolls++ }) = SealRollDebouncer(1_000, sched, roll)

    @Test
    fun requestRollsOnceAfterTheDelay() {
        val d = debouncer()
        d.request()
        assertEquals(0, rolls)
        sched.fireAll()
        assertEquals(1, rolls)
    }

    @Test
    fun aBurstCollapsesToOneRoll() {
        val d = debouncer()
        repeat(10) { d.request() }
        assertEquals(1, sched.pendingCount())
        sched.fireAll()
        assertEquals(1, rolls)
    }

    @Test
    fun aRequestDuringARollQueuesExactlyOneFollowUp() {
        lateinit var d: SealRollDebouncer
        d = debouncer {
            rolls++
            if (rolls == 1) repeat(5) { d.request() }
        }
        d.request()
        sched.fireAll()
        assertEquals(1, rolls)
        assertEquals("one queued follow-up, not five", 1, sched.pendingCount())
        sched.fireAll()
        assertEquals(2, rolls)
        assertEquals(0, sched.pendingCount())
    }

    @Test
    fun disposeCancelsPendingAndIgnoresLateFiresAndRequests() {
        val d = debouncer()
        d.request()
        d.dispose()
        assertEquals(0, sched.pendingCount())
        sched.fireCancelledToo()
        d.request()
        assertEquals(0, sched.pendingCount())
        assertEquals(0, rolls)
    }
}
