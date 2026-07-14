package dev.provenance.recorder.watch

import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.ExternalChangeResult
import dev.provenance.recorder.events.buildExternalChangeContent
import dev.provenance.recorder.events.classifySavedContent
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
     */
    fun onExternalDelete(relativePath: String): FsExternalChangePayload? {
        if (!registry.isWatched(relativePath)) return null
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
