package dev.provenance.recorder.session

/**
 * Counts appended entries and reports when a checkpoint is due (PRD §4.6: "signed every
 * N events"). Ported from the counter in provenance/packages/recorder/src/extension.ts
 * (`entryCountSinceLastCheckpoint >= CHECKPOINT_INTERVAL`). Pure, stateful, no I/O — this
 * is only the trigger; CheckpointScheduler does the signing and persisting.
 */
class CheckpointCadence(private val interval: Int = DEFAULT_INTERVAL) {
    init {
        require(interval > 0) { "interval must be positive" }
    }

    private var sinceLast = 0

    /** Call once per appended entry. Returns true (and resets the counter) when due. */
    fun onEntryAppended(): Boolean {
        sinceLast++
        if (sinceLast >= interval) {
            sinceLast = 0
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_INTERVAL = 100
    }
}
