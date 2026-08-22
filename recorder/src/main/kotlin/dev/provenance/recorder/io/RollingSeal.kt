package dev.provenance.recorder.io

import dev.provenance.core.BundleManifest
import dev.provenance.core.Sha256
import dev.provenance.core.SignedBundleManifest
import dev.provenance.core.SubmissionFileEntry
import dev.provenance.core.buildRollingSessionManifest
import dev.provenance.core.rollingManifestFilenames
import dev.provenance.core.signBundleManifest
import dev.provenance.recorder.commands.rethrowIfFatal
import java.nio.file.Files
import java.nio.file.Path

/**
 * The ROLLING SEAL, write side. Port of the VS Code recorder's `io/rolling-seal-writer.ts`;
 * `core/RollingManifest.kt` (and log-core's `rolling-manifest.ts`) is the design.
 *
 * A git-submitted assignment has no seal step: the student pushes, the grader clones,
 * nothing ever runs "Prepare Submission Bundle". So the seal moves off the submission event
 * and onto the recording itself. This module rewrites, on every checkpoint,
 *
 *   .provenance/manifest-<session_id>.json   BundleManifest at format_version 1.2
 *   .provenance/manifest-<session_id>.sig    ed25519 over the canonical JSON
 *
 * so whatever is committed to git is always a valid seal of the state at that moment. The
 * two rules this module exists to honour:
 *
 *   1. The file covers EXACTLY ONE session, whose `session_id` is non-null and equals the id
 *      in the filename. Both come from the SAME [sessionId] argument, via
 *      [buildRollingSessionManifest] and [rollingManifestFilenames] — nothing here spells a
 *      filename by hand, so writer and reader cannot drift.
 *   2. It is signed by THAT session's own ephemeral key — the same key whose public half is
 *      recorded in `session.start`. A rolling seal is verified against exactly one pubkey,
 *      so signing with anything else fails.
 *
 * ## The `final` marker
 *
 * Every roll but one is taken while the log is still growing, so its digests commit to a
 * PREFIX and a reader must not treat later bytes as tampering. The exception is the teardown
 * roll, taken after `session.end` is emitted and the writer flushed: that log is finished,
 * and saying so — `final: true`, inside the SIGNED payload — is what lets a reader enforce
 * whole-file equality and catch an entry appended after the session ended.
 *
 * The claim is the CALLER's to make, because only the caller knows the ordering. This module
 * just records it. Passing [isFinal] from a checkpoint would assert that a live log is
 * finished and turn the student's next keystroke into a finding, so exactly one call site
 * sets it: [dev.provenance.recorder.session.RecordingSessionController.endSession].
 *
 * ## What this module must never touch
 *
 * `manifest.json` / `manifest.sig`. Those are the CLASSIC seal, written only by
 * `commands/SealBundle.kt`. A classic sealed bundle keeps the same 1.1 manifest, the same
 * canonical bytes and the same signature it has today.
 *
 * ## Failure is never fatal
 *
 * Recording matters more than sealing. Every failure — the directory deleted by a
 * `git checkout`, a read-only checkout, a full disk, a filesystem that cannot rename —
 * comes back as [RollingSealResult.WriteError] (CLAUDE.md: errors are values when expected).
 * Nothing here throws except a [VirtualMachineError], so a checkpoint's sign-and-write cannot
 * be aborted by the seal and the session records on.
 */
sealed interface RollingSealResult {
    data class Written(
        val manifestPath: Path,
        val sigPath: Path,
        /** Exactly the bytes written to the `.json` — i.e. exactly what was signed. */
        val canonicalJson: String,
        val signatureHex: String,
    ) : RollingSealResult

    data class WriteError(val message: String) : RollingSealResult
}

private val SHA256_EMPTY = Sha256.hex(ByteArray(0))

/**
 * sha256 of a file's bytes, or of empty bytes when it cannot be read.
 *
 * The empty-bytes fallback matches the classic seal's existing defensive behaviour, and it
 * is what keeps this path safe when git moves the ground: a `git checkout` that removes the
 * `.slog` mid-session yields a well-formed 64-hex hash rather than an exception or an
 * unwritable manifest.
 */
internal fun rollingSha256OfFile(path: Path): String =
    try {
        Sha256.hex(Files.readAllBytes(path))
    } catch (e: Throwable) {
        rethrowIfFatal(e)
        SHA256_EMPTY
    }

/**
 * Rewrite this session's rolling seal to reflect the state right now.
 *
 * Steps:
 *   1. Hash the `.slog` and `.slog.meta` as they currently stand on disk.
 *   2. Hash every `files_under_review` entry; absent ones are recorded `status: "missing"`,
 *      exactly as the classic seal records them.
 *   3. Build a 1.2 manifest covering this one session.
 *   4. Canonicalize + sign with this session's own private key, through the same
 *      [signBundleManifest] the classic seal uses — so both shapes are produced identically.
 *   5. Atomically commit `.json` + `.sig` together.
 *
 * Never throws (a [VirtualMachineError] excepted — an OutOfMemoryError reported as a seal
 * write failure is a wrong diagnosis, and continuing after one is unsound).
 *
 * @param isFinal see [buildRollingSessionManifest]; only the teardown roll may pass true.
 */
fun writeRollingSeal(
    provenanceDir: Path,
    sessionId: String,
    prevSessionId: String?,
    slogPath: Path,
    workspaceRoot: Path,
    assignmentId: String,
    semester: String,
    filesUnderReview: List<String>,
    sessionPrivkey: ByteArray,
    extensionHash: String,
    isFinal: Boolean = false,
    /** Test seam for the file digests, so a test can drive the real writer with fixed hashes. */
    sha256OfFile: (Path) -> String = ::rollingSha256OfFile,
    /** Test seam for the signing step; production uses the real [signBundleManifest]. */
    signManifest: (BundleManifest, ByteArray) -> SignedBundleManifest = ::signBundleManifest,
    /** Test seam for the paired atomic write, so a test can force a rename failure. */
    writeFiles: (List<Pair<Path, String>>) -> Unit = ::atomicWriteFilePair,
): RollingSealResult {
    try {
        // Step 1: hashes of this session's own log files, as they are right now.
        val slogSha256 = sha256OfFile(slogPath)
        val metaSha256 = sha256OfFile(slogPath.resolveSibling("${slogPath.fileName}.meta"))

        // Step 2: on-disk state of the reviewed files. An ordered loop, deliberately: the
        // manifest's submission_files order must be stable across checkpoints, otherwise the
        // canonical bytes churn for no reason.
        val submissionFiles = ArrayList<SubmissionFileEntry>(filesUnderReview.size)
        for (rel in filesUnderReview) {
            submissionFiles.add(readSubmissionFile(workspaceRoot, rel))
        }

        // Step 3: exactly one session, non-null id, matching the filename built below from
        // the same `sessionId`.
        val manifest = buildRollingSessionManifest(
            sessionId = sessionId,
            prevSessionId = prevSessionId,
            slogSha256 = slogSha256,
            metaSha256 = metaSha256,
            assignmentId = assignmentId,
            semester = semester,
            extensionHash = extensionHash,
            submissionFiles = submissionFiles,
            isFinal = isFinal,
        )

        // Step 4: sign with THIS session's key.
        val signed = signManifest(manifest, sessionPrivkey)

        // Step 5: commit both files together.
        val names = rollingManifestFilenames(sessionId)
        val manifestPath = provenanceDir.resolve(names.json)
        val sigPath = provenanceDir.resolve(names.sig)

        // Deliberately NO createDirectories here. `.provenance/` is created once at session
        // start, and if a `git checkout` has since removed it then the `.slog` this manifest
        // claims to seal is gone too. Recreating the directory would leave a signed manifest
        // sealing a log that is not there — precisely the defect a reader exists to report —
        // and would let a straggling write resurrect a directory git just deleted. Failing
        // the seal and leaving the filesystem alone is the honest outcome.
        writeFiles(listOf(manifestPath to signed.canonicalJson, sigPath to signed.signatureHex))

        return RollingSealResult.Written(manifestPath, sigPath, signed.canonicalJson, signed.signatureHex)
    } catch (e: Throwable) {
        // Throwable, not Exception, and for the same reason the seal command widened: IJent
        // (a Windows IDE opening a project on the WSL filesystem) answers an unimplemented
        // filesystem operation with kotlin.NotImplementedError, and the ed25519 provider
        // initialises lazily so a broken provider raises NoClassDefFoundError /
        // ExceptionInInitializerError. An `Exception` catch lets all three straight out of
        // here and into the checkpoint coroutine — killing the seal AND the checkpoint that
        // carries it, on a path where recording must simply continue.
        rethrowIfFatal(e)
        return RollingSealResult.WriteError(e.message ?: e.toString())
    }
}

/** Read one `files_under_review` entry's on-disk state, or mark it missing. */
private fun readSubmissionFile(workspaceRoot: Path, relPath: String): SubmissionFileEntry =
    try {
        SubmissionFileEntry(relPath, "present", Sha256.hex(Files.readAllBytes(workspaceRoot.resolve(relPath))))
    } catch (_: Exception) {
        // Deliberately NOT widened to Throwable, matching the classic seal's reviewed-file
        // read: this catch's meaning is "record this file as missing", and that verdict goes
        // into a signed manifest. Letting a filesystem Error land here would sign a claim
        // that a file the student does have does not exist; the enclosing handler turns it
        // into a WriteError instead, which is a blameless coverage gap.
        SubmissionFileEntry(relPath, "missing", null)
    }
