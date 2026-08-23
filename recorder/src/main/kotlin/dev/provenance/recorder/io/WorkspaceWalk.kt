package dev.provenance.recorder.io

import dev.provenance.core.PathRole
import dev.provenance.core.ResolvedScope
import dev.provenance.core.SubmissionFileEntry
import dev.provenance.core.isExactEntry
import dev.provenance.core.isHardExcluded
import dev.provenance.core.resolvePathRole
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The shared workspace walk + submission-file collection, used by BOTH seals
 * (`commands/SealBundle.kt`, the classic seal, and `io/RollingSeal.kt`, the rolling
 * seal). Port of the VS Code recorder's `io/workspace-walk.ts`; design spec
 * `docs/superpowers/specs/2026-08-22-path-scope-design.md` §4.
 *
 * A `files_under_review` rule entry (`src/`, `*.java`) cannot be enumerated from the
 * manifest — the whole point of writing a rule is that the file set is not known in
 * advance — so the seal has to WALK the workspace and assign each discovered path a
 * role via [resolvePathRole]. Two independent copies of that walk, one per seal, is
 * exactly the divergence path scope exists to avoid: a course scoped to `src/` must
 * not get one seal that lists it and another that lists nothing. So this module is
 * the ONE walk (and the one collection routine built on top of it) both seals go
 * through — see [collectSubmissionFiles].
 */

/**
 * True if any path SEGMENT of [relPath] is exactly `.git` or `.provenance`.
 *
 * [walkWorkspace] prunes hard-excluded directories at the DIRECTORY level, so it only
 * ever needs to check the CURRENT segment. An EXACT `track` entry read directly by
 * string — never discovered by the walk, so never pruned by it — needs the same check
 * applied to its FULL path, which is why this is exported: [collectSubmissionFiles]'s
 * exact-entry loop calls it again, itself, against the manifest string.
 *
 * This is deliberately NOT [dev.provenance.core.isHardExcluded]: that function only
 * matches a path that STARTS WITH `.git/` or `.provenance/` (root-anchored), so it
 * says nothing about `vendor/lib/.git/` (a submodule) or — the case that matters most
 * here — a SIBLING assignment's `.provenance/` under this repo's nested/concurrent
 * multi-assignment recording. A rule entry like `*.json` would otherwise walk into
 * `hw3/.provenance/` and seal that assignment's signed manifest into THIS bundle,
 * leaking one student's provenance into another's evidence. `isHardExcluded` stays
 * root-anchored on purpose — it is pinned by the path-scope conformance vectors and
 * re-implemented by hand in the other recorder ports — and this walk-local rule
 * covers the deeper case instead. Both checks are applied together in [walkWorkspace]
 * as cheap, redundant insurance.
 */
fun hasHardExcludedSegment(relPath: String): Boolean = relPath.split("/").any { it == ".git" || it == ".provenance" }

/**
 * Result of walking one subtree: the files found, and whether any directory in it
 * refused to list.
 *
 * @property paths Every REGULAR file under the walked root, as workspace-relative
 *   forward-slash paths. Hard-excluded directories are never descended into, so
 *   nothing under `.git/` or `.provenance/` (at any depth, by segment name) appears
 *   here.
 * @property hadUnreadableDir True if [Files.newDirectoryStream] failed anywhere in
 *   this subtree — most often a permissions problem. A directory this walk cannot
 *   list is NOT silently treated as empty: whatever it held is absent from [paths]
 *   with no other trace, so the caller must disclose this rather than let a subtree's
 *   files vanish from the bundle unmentioned.
 * @property symlinkPaths Entries the walk saw as SYMLINKS and therefore did not
 *   report in [paths] — see [walkWorkspace]'s docstring for why they are not
 *   followed, and why they are recorded here rather than silently dropped.
 */
data class WalkResult(
    val paths: List<String>,
    val hadUnreadableDir: Boolean,
    val symlinkPaths: List<String>,
)

/**
 * Every REGULAR file under [root], as workspace-relative forward-slash paths.
 *
 * Hard-excluded directories are skipped at the DIRECTORY level rather than filtered
 * afterwards: a real `.git/` in an assignment holds thousands of objects, and walking
 * them just to throw them away is the difference between a seal that feels instant
 * and one that does not. The skip is by SEGMENT NAME (the directory entry's own
 * [Path.getFileName]), not [isHardExcluded]'s root-anchored prefix check — see
 * [hasHardExcludedSegment]'s docstring for why that distinction is load-bearing.
 * `isHardExcluded` is checked too, redundantly, as cheap insurance should the
 * hard-excluded prefix list ever grow past `.git`/`.provenance`.
 *
 * Symlinks are classified with [LinkOption.NOFOLLOW_LINKS] (an `lstat`, not a
 * `stat`) and are therefore NEVER traversed, NEVER read, and never reported in
 * [WalkResult.paths] — whether the link is to a file or a directory. Not following
 * a link is non-negotiable: following one could spin this walk forever on a cycle,
 * or walk straight out of the workspace through `ln -s / escape`. But the entry is
 * still RECORDED, in [WalkResult.symlinkPaths], by its own workspace-relative path
 * (never by its target — the target is exactly what this walk refuses to resolve),
 * so the caller can disclose the drop rather than let an in-scope symlinked file
 * vanish from the evidence bundle without a trace.
 *
 * A directory this function cannot list (most often a permissions problem) does not
 * make its subtree look empty: [WalkResult.hadUnreadableDir] bubbles that up so the
 * caller can warn rather than silently seal nothing from it.
 */
fun walkWorkspace(root: Path, rel: String = ""): WalkResult {
    val dir = if (rel.isEmpty()) root else root.resolve(rel)
    val entries = try {
        Files.newDirectoryStream(dir).use { it.toList() }
    } catch (e: Exception) {
        return WalkResult(emptyList(), hadUnreadableDir = true, emptyList())
    }
    val out = ArrayList<String>()
    val symlinkPaths = ArrayList<String>()
    var hadUnreadableDir = false
    for (child in entries) {
        val name = child.fileName.toString()
        val childRel = if (rel.isEmpty()) name else "$rel/$name"
        when {
            // Checked FIRST: an lstat-style symlink test, so a symlinked directory is
            // never mistaken for a real one and traversed.
            Files.isSymbolicLink(child) -> {
                if (!hasHardExcludedSegment(childRel) && !isHardExcluded(childRel)) {
                    symlinkPaths.add(childRel)
                }
                // A link inside an already-pruned `.git/`/`.provenance/` is never reached
                // in the first place (its parent directory was skipped below), so this
                // check is mostly redundant insurance — kept for the same reason the
                // directory branch keeps its own.
            }
            Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> {
                if (hasHardExcludedSegment(name) || isHardExcluded("$childRel/")) continue
                val sub = walkWorkspace(root, childRel)
                out.addAll(sub.paths)
                symlinkPaths.addAll(sub.symlinkPaths)
                if (sub.hadUnreadableDir) hadUnreadableDir = true
            }
            Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                out.add(childRel)
            }
            // Anything else (FIFO, socket, device) is neither a file, a directory, nor a
            // symlink — not reported. `readWorkspaceFile`'s own regular-file gate is what
            // protects an EXACT entry naming one of these; the walk simply never surfaces
            // it as a candidate in the first place.
        }
    }
    return WalkResult(out, hadUnreadableDir, symlinkPaths)
}

/** One file the collection routine kept, alongside its raw bytes when [includeBytes] asked for them. */
data class CollectedFile(val entry: SubmissionFileEntry, val bytes: ByteArray?)

/**
 * Every distinct fact [collectSubmissionFiles] can report about something it DROPPED
 * rather than sealed. Kept as separate booleans on purpose — see the design spec §3.4
 * table: "resolved outside the workspace", "could not be read", "a whole directory
 * could not be listed", "dropped as a duplicate of an already-sealed file", and
 * "a symlink the walk declined to follow" are five different facts about a student's
 * submission, and staff need to be able to tell them apart. Collapsing them into one
 * boolean is exactly the thing this type exists to prevent.
 */
data class SubmissionFileCollection(
    val files: List<CollectedFile>,
    /** A walk-discovered or exact in-scope path whose bytes could not be read. */
    val unreadableFile: Boolean = false,
    /** An in-scope path that resolves outside the workspace root. */
    val outOfWorkspaceFile: Boolean = false,
    /** An in-scope path that names a directory, FIFO, socket, or device, not a file. */
    val nonRegularFile: Boolean = false,
    /** A directory under the workspace that could not be listed while walking. */
    val unreadableDirectory: Boolean = false,
    /** An exact entry dropped because it real-paths to a file the walk already sealed. */
    val duplicateEntryDropped: Boolean = false,
    /** An in-scope symlink the walk declined to follow and no exact entry rescued. */
    val inScopeSymlinkSkipped: Boolean = false,
)

/**
 * Every in-scope file's current on-disk state, as [SubmissionFileEntry] values ready
 * to drop into a [dev.provenance.core.BundleManifest], plus every distinct DROP fact
 * along the way. Shared by the classic seal (`commands/SealBundle.kt`) and the
 * rolling seal (`io/RollingSeal.kt`) so their collection logic cannot drift.
 *
 * Three parts, in order — mirrors the upstream VS Code recorder's `seal.ts` step 3:
 *
 * 1. **Walk** the workspace ([walkWorkspace]) and assign each discovered path a role
 *    via [resolvePathRole]. Keep only `reviewed` and `attachment`. Anything that does
 *    not read cleanly here is DROPPED with its own distinct fact — never recorded
 *    `missing`: this path was discovered by the walk, not asserted by the manifest,
 *    and a rule entry ([isExactEntry] false) asserts nothing about any one file's
 *    existence.
 * 2. Every EXACT `scope.track` entry the walk did NOT already sight gets its own
 *    read attempt, in `scope.track` order (so the manifest's `submission_files`
 *    order is stable). **This is the ONLY loop that may mint a `missing` record**,
 *    and only for [WorkspaceFileRead.Missing] — genuine absence. The sighting set is
 *    built from what the walk SAW (step 1's `sightedInScope`, populated before the
 *    read is even attempted), never from what it successfully READ: a 1.x manifest's
 *    `files_under_review` is nothing but exact entries, so a file the walk saw but
 *    could not reopen would otherwise fall through to this loop and mint a false
 *    `missing`. The hard-excluded-segment check is re-applied here too, against the
 *    manifest STRING, because this loop reads by string and never passes through the
 *    walk's own directory-level pruning.
 * 3. **Disclose** every in-scope symlink the walk declined to follow that step 2 did
 *    not rescue (an exact entry naming a symlinked file IS sealed, because reading by
 *    string follows the link — only a rule-matched or attachment symlink has no
 *    rescue).
 *
 * @param includeBytes Whether a present file's raw bytes are carried in the result.
 *   The classic seal's ZIP step needs them; the rolling seal only needs the hash.
 */
fun collectSubmissionFiles(
    workspaceRoot: Path,
    scope: ResolvedScope,
    includeBytes: Boolean,
): SubmissionFileCollection {
    // Fail CLOSED: if the root itself cannot be realpath'd, fall back to its lexical
    // form. Every candidate's own realpath will then fail to match this fallback, so
    // every candidate is rejected as out-of-workspace rather than opened unverified —
    // see `readWorkspaceFile`'s own docstring for the same reasoning applied per-file.
    val workspaceRootReal = try {
        workspaceRoot.toRealPath()
    } catch (e: Exception) {
        workspaceRoot.toAbsolutePath().normalize()
    }

    val walkResult = walkWorkspace(workspaceRoot)

    var unreadableFile = false
    var outOfWorkspaceFile = false
    var nonRegularFile = false
    var duplicateEntryDropped = false

    // SIGHTINGS, not successful reads — see this function's docstring, step 2. A path
    // the walk saw is recorded here BEFORE its read is attempted.
    val sightedInScope = HashSet<String>()
    data class Found(val path: String, val role: String, val status: String, val sha256: String?, val bytes: ByteArray?)
    val found = ArrayList<Found>()

    // Step 1: walk-discovered files.
    for (rel in walkResult.paths) {
        val role = resolvePathRole(rel, scope)
        if (role != PathRole.REVIEWED && role != PathRole.ATTACHMENT) continue
        sightedInScope.add(rel)
        when (val read = readWorkspaceFile(workspaceRoot, workspaceRootReal, rel, includeBytes)) {
            is WorkspaceFileRead.Present -> found.add(Found(rel, role.wire, "present", read.sha256, read.bytes))
            // Dropped, never `missing` — this path was discovered by the walk, not
            // asserted by the manifest, and it is not retried in step 2 either (it is
            // already in `sightedInScope`).
            WorkspaceFileRead.Missing -> unreadableFile = true
            WorkspaceFileRead.Unreadable -> unreadableFile = true
            WorkspaceFileRead.OutOfWorkspace -> outOfWorkspaceFile = true
            WorkspaceFileRead.NonRegular -> nonRegularFile = true
        }
    }

    // Real-path cache for the exact-entry dedupe below. Lazy: a collection with no
    // exact entry colliding with an already-walked file never calls toRealPath here.
    val realPathCache = HashMap<String, Path>()
    fun realPathOf(relPath: String): Path = realPathCache.getOrPut(relPath) {
        try {
            workspaceRoot.resolve(relPath).toRealPath()
        } catch (e: Exception) {
            workspaceRoot.resolve(relPath).toAbsolutePath().normalize()
        }
    }

    // Step 2: exact track entries the walk did not already sight.
    for (entry in scope.track) {
        if (!isExactEntry(entry)) continue
        if (resolvePathRole(entry, scope) != PathRole.REVIEWED) continue
        // The walk's own directory-level pruning never sees an entry read directly by
        // string — apply the same segment check here.
        if (hasHardExcludedSegment(entry)) continue
        if (sightedInScope.contains(entry)) continue

        when (val read = readWorkspaceFile(workspaceRoot, workspaceRootReal, entry, includeBytes)) {
            is WorkspaceFileRead.Present -> {
                // A case-insensitive filesystem or a symlink can make this exact entry
                // read successfully while pointing at the SAME underlying bytes the walk
                // already sealed under a different spelling. Reconcile by REAL path, not
                // by string, or the same file is sealed twice.
                val candidateReal = realPathOf(entry)
                val duplicate = found.any { it.status == "present" && realPathOf(it.path) == candidateReal }
                if (duplicate) {
                    duplicateEntryDropped = true
                } else {
                    found.add(Found(entry, PathRole.REVIEWED.wire, "present", read.sha256, read.bytes))
                }
            }
            // The ONLY place that may mint `missing` — an exact entry's genuine absence.
            WorkspaceFileRead.Missing -> found.add(Found(entry, PathRole.REVIEWED.wire, "missing", null, null))
            WorkspaceFileRead.Unreadable -> unreadableFile = true
            WorkspaceFileRead.OutOfWorkspace -> outOfWorkspaceFile = true
            WorkspaceFileRead.NonRegular -> nonRegularFile = true
        }
    }

    // Step 3: disclose in-scope symlinks the walk declined that step 2 did not rescue.
    val sealedPaths = found.mapTo(HashSet()) { it.path }
    var inScopeSymlinkSkipped = false
    for (link in walkResult.symlinkPaths) {
        val role = resolvePathRole(link, scope)
        if (role != PathRole.REVIEWED && role != PathRole.ATTACHMENT) continue
        if (sealedPaths.contains(link)) continue
        inScopeSymlinkSkipped = true
        break
    }

    return SubmissionFileCollection(
        files = found.map { CollectedFile(SubmissionFileEntry(it.path, it.status, it.sha256, it.role), it.bytes) },
        unreadableFile = unreadableFile,
        outOfWorkspaceFile = outOfWorkspaceFile,
        nonRegularFile = nonRegularFile,
        unreadableDirectory = walkResult.hadUnreadableDir,
        duplicateEntryDropped = duplicateEntryDropped,
        inScopeSymlinkSkipped = inScopeSymlinkSkipped,
    )
}
