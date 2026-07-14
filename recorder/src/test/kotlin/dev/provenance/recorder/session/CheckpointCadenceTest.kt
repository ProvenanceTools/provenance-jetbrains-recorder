package dev.provenance.recorder.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic — JUnit 4 (recorder module runs on JUnit 4; see build.gradle.kts). */
class CheckpointCadenceTest {
    @Test
    fun `default interval fires on the 100th entry and not before`() {
        val cadence = CheckpointCadence()
        repeat(99) { i -> assertFalse("entry ${i + 1} should not be due", cadence.onEntryAppended()) }
        assertTrue("100th entry should be due", cadence.onEntryAppended())
    }

    @Test
    fun `counter resets after a due checkpoint (not a one-shot latch)`() {
        val cadence = CheckpointCadence()
        repeat(100) { cadence.onEntryAppended() }
        // Next 99 should be false again, 200th true.
        repeat(99) { i -> assertFalse("post-reset entry ${i + 1}", cadence.onEntryAppended()) }
        assertTrue("200th entry should be due", cadence.onEntryAppended())
    }

    @Test
    fun `custom interval is honored independent of the pinned default`() {
        val cadence = CheckpointCadence(interval = 3)
        val dueOn = (1..9).filter { cadence.onEntryAppended() }.toList()
        assertEquals(listOf(3, 6, 9), dueOn)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero interval throws at construction`() {
        CheckpointCadence(interval = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative interval throws at construction`() {
        CheckpointCadence(interval = -1)
    }

    @Test
    fun `default interval constant matches the pinned CHECKPOINT_INTERVAL of 100`() {
        assertEquals(100, CheckpointCadence.DEFAULT_INTERVAL)
    }
}
