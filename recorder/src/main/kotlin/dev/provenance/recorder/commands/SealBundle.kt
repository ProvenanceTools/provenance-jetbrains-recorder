package dev.provenance.recorder.commands

import dev.provenance.core.BundleManifest
import dev.provenance.core.ChainCheck
import dev.provenance.core.ParseResult
import dev.provenance.core.ResolvedScope
import dev.provenance.core.SessionEntry
import dev.provenance.core.Sha256
import dev.provenance.core.SignedBundleManifest
import dev.provenance.core.parseEntries
import dev.provenance.core.signBundleManifest
import dev.provenance.core.validateChain
import dev.provenance.recorder.io.atomicWriteFile
import dev.provenance.recorder.io.collectSubmissionFiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundle seal (PRD §4.6 seal, §5.3 bundle = ZIP of .provenance/ + submission files).
 * Direct port of seal.ts, including the "never abort on a broken/unparseable chain,
 * accumulate warnings instead" policy. Uses the JDK's java.util.zip (no jszip).
 * Never modifies manifest.json / manifest.sig after writing — they are signed.
 *
 * This module owns the invariant "what goes in the zip must be openable"; the decision half
 * lives in `BundleOrphanGuard.kt`, which both this command and the cross-implementation
 * gate's git-submission packer go through so the gate cannot drift from what ships.
 */
sealed interface SealResult {
    data class Ok(
        val bundlePath: Path,
        val manifestSha256: String,
        val chainBroken: Boolean,
        val unreadableSession: Boolean,
        /**
         * Disclosure for in-scope entries the read-and-classify step in
         * `WorkspaceFileRead.kt` had to DROP rather than seal — see that file for the
         * invariant. Distinct facts on purpose (never collapsed into one "something was
         * dropped" bit): [unreadableFile] means bytes could not be read at all;
         * [outOfWorkspaceFile] means a path (almost always an innocent symlink) resolved
         * outside the workspace root; [nonRegularFile] means a directory, FIFO, socket, or
         * device sat where a file was expected — the ordinary staff typo `src` instead of
         * `src/`. None of these may ever seal as `status: "missing"`, which is reserved for
         * a path that genuinely does not exist. Defaulted so every existing construction and
         * call site is unaffected.
         */
        val unreadableFile: Boolean = false,
        val outOfWorkspaceFile: Boolean = false,
        val nonRegularFile: Boolean = false,
        /**
         * Disclosure introduced by the workspace WALK (`io/WorkspaceWalk.kt`), needed
         * because a rule entry (`src/`, `*.java`) is discovered rather than asserted —
         * see design spec §3.4 and §4. Kept distinct from the three flags above for the
         * same reason they are distinct from each other: staff need to tell "a whole
         * directory could not be listed" apart from "one file could not be read", and
         * "the bytes are sealed under a different spelling" apart from "the bytes are not
         * in the bundle at all".
         */
        val unreadableScopeDirectory: Boolean = false,
        val duplicateEntryDropped: Boolean = false,
        val inScopeSymlinkSkipped: Boolean = false,
        /**
         * Artifacts the ORPHAN GUARD (`BundleOrphanGuard.kt`) had to leave out so the archive
         * stays openable. Defaulted so every existing construction and call site is unaffected;
         * [anythingDropped] is what the seal UI reads.
         */
        val orphanedSlog: Boolean = false,
        val orphanedMeta: Boolean = false,
        val emptySession: Boolean = false,
        val orphanedRollingSeal: Boolean = false,
    ) : SealResult {
        /** True when the bundle is missing something that was on disk. Never silent. */
        val anythingDropped: Boolean
            get() = orphanedSlog || orphanedMeta || emptySession || orphanedRollingSeal ||
                unreadableFile || outOfWorkspaceFile || nonRegularFile ||
                unreadableScopeDirectory || duplicateEntryDropped || inScopeSymlinkSkipped

        /** Human-readable list of what was left out, for the seal notification. */
        fun droppedDescriptions(): List<String> = buildList {
            if (emptySession) add("a session that recorded nothing before it ended")
            if (orphanedSlog) add("a session log with no metadata file beside it")
            if (orphanedMeta) add("a session metadata file with no log beside it")
            if (orphanedRollingSeal) add("a signed receipt for a session that is not in the bundle")
            if (unreadableFile) add("a tracked file that could not be read at seal time")
            if (outOfWorkspaceFile) add("a tracked path that resolves outside the workspace")
            if (nonRegularFile) add("a tracked path that names a directory or other non-file item, not a file")
            if (unreadableScopeDirectory) add("a directory under the workspace that could not be listed")
            if (duplicateEntryDropped) add("a tracked file already sealed under a different path spelling")
            if (inScopeSymlinkSkipped) add("a symlink in scope that the seal declined to follow")
        }
    }

    data object NoSessions : SealResult

    data class WriteError(val message: String) : SealResult
}

private val SHA256_EMPTY = Sha256.hex(ByteArray(0))

private fun sha256OfFile(path: Path): String =
    if (Files.exists(path)) Sha256.hex(Files.readAllBytes(path)) else SHA256_EMPTY

/**
 * The seal path models failure as a returned [SealResult.WriteError] the UI can show,
 * because a seal that dies costs a student their whole submission, not one event. That
 * has to include Errors: IJent (a Windows IDE opening a project on the WSL filesystem)
 * answers an unimplemented filesystem operation with kotlin.NotImplementedError, which
 * `catch (e: Exception)` lets through as a raw crash out of the seal action.
 *
 * A VirtualMachineError is the one thing that is never a seal failure — an OutOfMemoryError
 * reported as "Failed to write bundle ZIP" is a wrong diagnosis and continuing after one is
 * unsound. Same dividing line as SessionWriter, chosen over enumerating subclasses so future
 * JVM-fatal errors land on the correct side by default.
 *
 * Applied only to the steps whose failure already means "the seal cannot proceed". The two
 * narrow `catch (_: Exception)` sites (a reviewed file read, and the zip-loop read) stay
 * narrow on purpose — see the comments there.
 */
internal fun rethrowIfFatal(t: Throwable) {
    if (t is VirtualMachineError) throw t
}

/** ISO timestamp with colons replaced by dashes, for use in filenames. */
private fun filenameTimestamp(instant: Instant): String = instant.toString().replace(":", "-")

fun sealBundle(
    provenanceDir: Path,
    workspaceRoot: Path,
    assignmentId: String,
    semester: String,
    scope: ResolvedScope,
    sessionPrivkey: ByteArray,
    computeExtensionHash: () -> String,
    /**
     * Whether the LIVE session's expected-content registry reported its cap bit
     * ([dev.provenance.recorder.state.ExpectedContentRegistry.capHit]). Threaded from
     * the call site ([dev.provenance.recorder.session.RecorderSessionManager.sealSession])
     * rather than recomputed here — the registry is a live, in-memory structure that
     * does not survive a session's end, so this function has no way to reconstruct it.
     */
    scopeCapped: Boolean = false,
    outputDir: Path = workspaceRoot,
    now: () -> Instant = Instant::now,
    /** Test seam for the manifest/sig write, alongside the existing now/computeExtensionHash seams. */
    writeFile: (Path, String) -> Unit = { path, contents -> atomicWriteFile(path, contents) },
    /** Test seam for the manifest signing step; production uses the real [signBundleManifest]. */
    signManifest: (BundleManifest, ByteArray) -> SignedBundleManifest = ::signBundleManifest,
): SealResult {
    // Step 1: list .provenance/ and decide which sessions this bundle can carry.
    //
    // The whole directory, not just the `.slog`s: the ORPHAN GUARD pairs each log with its
    // `.slog.meta` by name, and an unpaired or contentless half is one the analyzer rejects
    // the ENTIRE bundle over. See BundleOrphanGuard.kt.
    if (!Files.isDirectory(provenanceDir)) return SealResult.NoSessions
    val dirEntryNames = try {
        Files.list(provenanceDir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
    } catch (e: Throwable) {
        // WriteError, deliberately NOT NoSessions. The isDirectory check above is the checked,
        // non-racy "this workspace has no recording" verdict; reaching here means the directory
        // existed and we still could not read it (permissions, IO error, or the dir vanishing
        // between the two calls). Reporting that as "no session data to seal" would tell a
        // student their work is absent when it may be sitting right there, unreadable.
        rethrowIfFatal(e)
        return SealResult.WriteError("Failed to list session files in $provenanceDir: ${e.message}")
    }
    val packable = selectPackableSessions(dirEntryNames) { name ->
        // Negative means "could not determine", which the guard treats as NOT empty. A stat
        // that fails must never be the reason a student's session is left out; if the file is
        // genuinely unreadable, step 2's read fails loudly with a WriteError instead.
        runCatching { Files.size(provenanceDir.resolve(name)) }.getOrDefault(-1L)
    }
    val slogFiles = packable.slogNames
    if (slogFiles.isEmpty()) return SealResult.NoSessions

    // Step 2: parse + validate each .slog. Warnings accumulate; never abort.
    var chainBroken = false
    var unreadableSession = false
    val sessions = ArrayList<SessionEntry>(slogFiles.size)

    // LOGICAL ids of the sessions this bundle will actually carry, for the rolling-seal half
    // of the guard in step 6. TWO-UUID RULE: `session.start.data.session_id`, never the `.slog`
    // filename's uuid — see logicalSessionIdOf.
    val packedSessionIds = HashSet<String>(slogFiles.size)

    for (filename in slogFiles) {
        val slogPath = provenanceDir.resolve(filename)
        val metaPath = provenanceDir.resolve("$filename.meta")
        val slogText = try {
            String(Files.readAllBytes(slogPath), Charsets.UTF_8)
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            return SealResult.WriteError("Failed to read $filename: ${e.message}")
        }

        // sha256OfFile checks Files.exists and then reads — a TOCTOU: a file that disappears
        // between the two calls throws out of readAllBytes. Unguarded, that escaped sealBundle
        // with no SealResult at all, so the seal action never told the student it had died.
        // These hashes also go into the SIGNED manifest, so a read failure must never be
        // quietly folded into the "absent file" empty hash — that would sign a claim that a
        // session the student recorded does not exist.
        val slogSha: String
        val metaSha: String
        try {
            slogSha = sha256OfFile(slogPath)
            metaSha = sha256OfFile(metaPath)
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            return SealResult.WriteError("Failed to hash $filename: ${e.message}")
        }

        when (val parsed = parseEntries(slogText)) {
            is ParseResult.Err -> {
                unreadableSession = true
                sessions.add(SessionEntry(null, null, slogSha, metaSha))
            }
            is ParseResult.Ok -> {
                if (validateChain(parsed.entries) != ChainCheck.Valid) chainBroken = true
                val sessionId = logicalSessionIdOf(parsed.entries)
                val prevSessionId = prevSessionIdOf(parsed.entries)
                if (sessionId == null) unreadableSession = true else packedSessionIds.add(sessionId)
                sessions.add(SessionEntry(sessionId, prevSessionId, slogSha, metaSha))
            }
        }
    }

    // Step 3: walk the workspace and assign each file its role, through the shared
    // collect-and-classify logic in WorkspaceWalk.kt — a rule entry (`src/`, `*.java`)
    // cannot be enumerated from the manifest, so the file set is DISCOVERED here rather
    // than read off a list. See that file for why `missing` may only mean genuine
    // absence, and for the full partition of drop facts.
    val collected = collectSubmissionFiles(workspaceRoot, scope, includeBytes = true)
    val submissionFiles = collected.files.map { it.entry }

    // Step 4: build the 1.1 manifest.
    val extensionHash = try {
        computeExtensionHash()
    } catch (e: Throwable) {
        rethrowIfFatal(e)
        return SealResult.WriteError("Failed to compute extension hash: ${e.message}")
    }
    val manifest = BundleManifest(
        formatVersion = "1.1",
        assignmentId = assignmentId,
        semester = semester,
        extensionHash = extensionHash,
        sessions = sessions,
        submissionFiles = submissionFiles,
        scopeCapped = scopeCapped,
    )

    // Step 5: sign + atomic-write manifest.json (the exact signed bytes) and manifest.sig.
    val signed = try {
        signManifest(manifest, sessionPrivkey)
    } catch (e: Throwable) {
        // Widened for the same reason as the other seal steps, from a different cause: the
        // ed25519 provider initialises lazily at the first sign() call, so a provider whose
        // class init fails raises NoClassDefFoundError / ExceptionInInitializerError — Errors
        // an `Exception` catch lets straight out of sealBundle. Different failure class from
        // the IJent NotImplementedError, identical consequence on this path: a dead seal with
        // no typed result and no notification, and no second chance for the student.
        rethrowIfFatal(e)
        return SealResult.WriteError("Failed to sign manifest: ${e.message}")
    }
    val manifestPath = provenanceDir.resolve("manifest.json")
    val sigPath = provenanceDir.resolve("manifest.sig")
    try {
        writeFile(manifestPath, signed.canonicalJson)
        writeFile(sigPath, signed.signatureHex)
    } catch (e: Throwable) {
        rethrowIfFatal(e)
        return SealResult.WriteError("Failed to write manifest/sig: ${e.message}")
    }
    val manifestSha256 = Sha256.hex(signed.canonicalJson.toByteArray(Charsets.UTF_8))

    // Step 6: zip what the ORPHAN GUARD says the analyzer can open, + present reviewed files.
    val ts = filenameTimestamp(now())
    val bundlePath = outputDir.resolve("$assignmentId-bundle-$ts.zip")
    var orphanedRollingSeal = false
    try {
        Files.createDirectories(outputDir)
        ZipOutputStream(Files.newOutputStream(bundlePath)).use { zip ->
            val dirFiles = Files.list(provenanceDir).use { s ->
                s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList()
            }
            // Re-listed AFTER the manifest write, so manifest.json / manifest.sig are in it.
            val selection = selectZipEntries(dirFiles, packable.names, packedSessionIds)
            orphanedRollingSeal = selection.orphanedRollingSeal
            for (name in selection.names) {
                val bytes = try {
                    Files.readAllBytes(provenanceDir.resolve(name))
                } catch (_: Exception) {
                    // Deliberately NOT widened, same reasoning as WorkspaceFileRead.kt's read
                    // step: "skip" means the file vanished between listing and read. An Error
                    // silently omitting a .slog would ship a bundle with a missing session.
                    // An Error here is caught by the enclosing handler and fails the seal.
                    continue // disappeared between listing and read — skip
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for (f in collected.files) {
                if (f.entry.status == "present" && f.bytes != null) {
                    zip.putNextEntry(ZipEntry(f.entry.path))
                    zip.write(f.bytes)
                    zip.closeEntry()
                }
            }
        }
    } catch (e: Throwable) {
        rethrowIfFatal(e)
        return SealResult.WriteError("Failed to write bundle ZIP: ${e.message}")
    }

    return SealResult.Ok(
        bundlePath = bundlePath,
        manifestSha256 = manifestSha256,
        chainBroken = chainBroken,
        unreadableSession = unreadableSession,
        unreadableFile = collected.unreadableFile,
        outOfWorkspaceFile = collected.outOfWorkspaceFile,
        nonRegularFile = collected.nonRegularFile,
        unreadableScopeDirectory = collected.unreadableDirectory,
        duplicateEntryDropped = collected.duplicateEntryDropped,
        inScopeSymlinkSkipped = collected.inScopeSymlinkSkipped,
        orphanedSlog = packable.orphanedSlog,
        orphanedMeta = packable.orphanedMeta,
        emptySession = packable.emptySession,
        orphanedRollingSeal = orphanedRollingSeal,
    )
}
