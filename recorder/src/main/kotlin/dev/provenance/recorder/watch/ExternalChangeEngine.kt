package dev.provenance.recorder.watch

import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.ExternalChangeResult
import dev.provenance.recorder.events.buildExternalChangeContent
import dev.provenance.recorder.events.classifySavedContent
import dev.provenance.recorder.state.ExpectedContent
import dev.provenance.recorder.state.ExpectedContentRegistry
import kotlin.math.abs

/**
 * The pure heart of external-change detection. Owns the [ExpectedContentRegistry] and
 * turns "here is what the disk now holds for a watched path" into an optional
 * fs.external_change payload, applying the fixed comparison direction (old = expected
 * model, new = on-disk reality) and reseeding the model afterwards.
 *
 * NO IntelliJ types — the three platform listeners (save-time check, VFS
 * BulkFileListener, reload-from-disk) are thin wrappers that resolve a relative path +
 * read disk content, then call one of these methods. This keeps the direction/dedup/
 * payload logic in pure functions that a plain JUnit test pins (CLAUDE.md), matching
 * the monorepo split between external-change-detector.ts (pure) and fs-watcher.ts
 * (wiring).
 *
 * Every method gates on [registry].isWatched — VFS/document listeners are
 * application-level and see every project's files, so this is the session scope filter.
 * Every method mutates the registry (reset/getOrCreate/delete) so the NEXT comparison
 * chains from reality; callers must not also reset.
 */
class ExternalChangeEngine(val registry: ExpectedContentRegistry) {

    /**
     * Recent-state tolerance (recorder PRD §4.5).
     *
     * Every content read reaching this engine was taken at some earlier instant than the
     * comparison that follows: [VfsExternalChangeListener] triages on the EDT and hands
     * the read + compare to a pooled background thread, while the expected-content model
     * is fed from the EDT by ExternalChangeCoordinator's DocumentListener. A keystroke
     * processed in that gap advances the model past the bytes actually written, so a bare
     * hash compare reports the student's own save as an external write — and the
     * `expected.reset(onDiskContent)` that followed rolled the model BACKWARDS onto the
     * stale snapshot, guaranteeing the next save mismatched too (a mirrored PAIR of false
     * events; 3316 of them across a 156-submission VS Code corpus).
     *
     * If the on-disk hash is a state this buffer genuinely passed through, the write was
     * ours and we merely observed it late: emit nothing, and do NOT reset — the live
     * buffer is ahead and is authoritative. Content the buffer never held still falls
     * through and is reported, so detection of genuine external writes is unchanged.
     */
    private fun isOurOwnLateObservedWrite(expected: ExpectedContent, newHash: String): Boolean =
        expected.hasRecentHash(newHash)

    /**
     * Path 1 — the editor just saved [relativePath]; [onDiskContent] is what landed on
     * disk. Only fires for a file that was open (has a registry entry). Emits a modify
     * if the saved content diverged from the expected model (e.g. format-on-save, or a
     * save racing an external write). Mirrors compareSavedContent's caller.
     */
    fun onSavedContent(relativePath: String, onDiskContent: String): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
        val expected = registry.get(relativePath) ?: return null
        return when (val r = classifySavedContent(expected, onDiskContent)) {
            is ExternalChangeResult.CleanSave -> null
            is ExternalChangeResult.Changed -> {
                if (isOurOwnLateObservedWrite(expected, r.newHash)) return null
                val payload = modifyPayload(relativePath, r.oldHash, r.newHash, r.diffSize, onDiskContent)
                expected.reset(onDiskContent)
                payload
            }
        }
    }

    /**
     * Path 2 — a VFS content change NOT mediated by the editor (external write / CLI /
     * git). Requires an existing baseline (never opened → skipped, mirrors
     * fs-watcher.ts handleChange). Identical content → no emit.
     */
    fun onExternalModify(relativePath: String, onDiskContent: String): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
        val expected = registry.get(relativePath) ?: return null
        return when (val r = classifySavedContent(expected, onDiskContent)) {
            is ExternalChangeResult.CleanSave -> null
            is ExternalChangeResult.Changed -> {
                if (isOurOwnLateObservedWrite(expected, r.newHash)) return null
                val payload = modifyPayload(relativePath, r.oldHash, r.newHash, r.diffSize, onDiskContent)
                expected.reset(onDiskContent)
                payload
            }
        }
    }

    /**
     * A watched file appeared on disk. If a doc.open already seeded the registry, treat
     * a divergence as a modify against that baseline (silent if identical); otherwise a
     * pure create with old_hash = "". Mirrors fs-watcher.ts handleCreate.
     */
    fun onExternalCreate(relativePath: String, onDiskContent: String): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
        val newHash = Sha256.hex(onDiskContent)
        val existing = registry.get(relativePath)
        if (existing != null) {
            if (newHash == existing.hash) return null
            if (isOurOwnLateObservedWrite(existing, newHash)) return null
            val payload = modifyPayload(
                relativePath, existing.hash, newHash,
                abs(onDiskContent.length - existing.content.length), onDiskContent,
            )
            existing.reset(onDiskContent)
            return payload
        }
        val content = buildExternalChangeContent(onDiskContent)
        val payload = FsExternalChangePayload(
            path = relativePath,
            oldHash = "",
            newHash = newHash,
            diffSize = onDiskContent.length,
            operation = "create",
            newContentSize = content.newContentSize,
            newContent = content.newContent,
            newContentHead = content.newContentHead,
            newContentTail = content.newContentTail,
        )
        registry.getOrCreate(relativePath, onDiskContent)
        return payload
    }

    /**
     * A watched file was deleted from disk. Emits operation = "delete" with old_hash from
     * the registry (or "" if never opened) and new_hash = "". Drops the registry entry so
     * a re-create starts clean. Mirrors fs-watcher.ts handleDelete.
     *
     * ## Why [confirmedAbsent] is required, and not an optional check
     *
     * The other two external paths already derive their verdict from OBSERVED STATE: a
     * mislabelled create self-corrects to a modify by consulting the registry baseline, and
     * a modify with no baseline returns null rather than inventing one. Both fail toward
     * SILENCE. Delete was the one path that failed toward ASSERTION — it took the platform's
     * event kind on trust and emitted unconditionally.
     *
     * That asymmetry was the defect, more than any specific platform behaviour. A missing
     * fact degrades the record; a WRONG fact enters a hash-chained, signed log as an
     * affirmative claim about a student and survives into adjudication. "This file was
     * deleted" about a file sitting on disk is exactly that claim.
     *
     * Path scope is why this moved now rather than later: before it, [ExpectedContentRegistry]
     * membership covered only exactly-named files, so a spurious delete needed an event on a
     * specific declared path. A folder rule means any file under `src/` can produce one.
     *
     * The parameter is REQUIRED rather than defaulted so a future caller cannot reintroduce
     * the unverified path by omission — the signature asks the question. The engine stays
     * pure (no I/O, no platform types, per this class's contract): the caller observes, this
     * decides. When absence cannot be CONFIRMED — an unreadable parent directory leaves both
     * `exists` and `notExists` false — the caller passes false and nothing is emitted, which
     * is the same fail-toward-silence direction the other two paths already take.
     */
    fun onExternalDelete(relativePath: String, confirmedAbsent: Boolean): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
        // Observed state, never the claimed event kind. The registry entry is deliberately
        // NOT dropped here: the file is still on disk, so its baseline is still correct.
        if (!confirmedAbsent) return null
        val expected = registry.get(relativePath)
        if (expected == null) {
            return FsExternalChangePayload(
                path = relativePath, oldHash = "", newHash = "", diffSize = 0, operation = "delete",
            )
        }
        val payload = FsExternalChangePayload(
            path = relativePath,
            oldHash = expected.hash,
            newHash = "",
            diffSize = expected.content.length,
            operation = "delete",
        )
        registry.delete(relativePath)
        return payload
    }

    /**
     * Path 3 — IntelliJ silently reloaded a clean buffer from disk ([content] is the
     * reloaded document text). No prior entry → seed silently; otherwise emit a modify on
     * divergence. This is the IntelliJ equivalent of VS Code's doc.change reload heuristic
     * but is an exact signal, not an inference.
     */
    fun onReload(relativePath: String, content: String): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
        val expected = registry.get(relativePath)
        if (expected == null) {
            registry.getOrCreate(relativePath, content)
            return null
        }
        return when (val r = classifySavedContent(expected, content)) {
            is ExternalChangeResult.CleanSave -> null
            is ExternalChangeResult.Changed -> {
                val payload = modifyPayload(relativePath, r.oldHash, r.newHash, r.diffSize, content)
                expected.reset(content)
                payload
            }
        }
    }

    private fun modifyPayload(
        relativePath: String,
        oldHash: String,
        newHash: String,
        diffSize: Int,
        onDiskContent: String,
    ): FsExternalChangePayload {
        val content = buildExternalChangeContent(onDiskContent)
        return FsExternalChangePayload(
            path = relativePath,
            oldHash = oldHash,
            newHash = newHash,
            diffSize = diffSize,
            operation = "modify",
            newContentSize = content.newContentSize,
            newContent = content.newContent,
            newContentHead = content.newContentHead,
            newContentTail = content.newContentTail,
        )
    }
}
