package dev.provenance.recorder.io

import dev.provenance.core.Sha256
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Read-and-classify one `files_under_review` entry, shared by the CLASSIC seal
 * (`commands/SealBundle.kt` step 3) and the ROLLING seal (`RollingSeal.kt`'s
 * `readSubmissionFile`) so their read logic cannot drift — two copies that must agree is
 * exactly the divergence that produced the bug this module fixes.
 *
 * ## Why this exists
 *
 * A `SubmissionFileEntry` with `status: "missing"` is baked into a SIGNED manifest and is
 * rendered to course staff as "listed in files_under_review but absent on disk at seal
 * time" — an affirmative claim about a student, used in academic-integrity proceedings.
 * Both seals used to route EVERY read failure through that verdict: a permission error, a
 * symlink loop, an I/O error, and — the common one — an entry that names a DIRECTORY
 * (the ordinary staff typo `src` instead of `src/`) all came out as "missing", which is
 * false. The file was there; it just could not be read, or was not a file at all.
 *
 * ## The invariant
 *
 * [WorkspaceFileRead.Missing] is reachable from EXACTLY ONE condition: the path genuinely
 * does not exist ([NoSuchFileException], from either the containment check or the read
 * itself). Every other failure classifies as [Unreadable], [OutOfWorkspace], or
 * [NonRegular] — DROPPED from the signed manifest by the caller, never folded into
 * `missing` and never silently sealed as present. Those three stay distinct facts on
 * purpose: staff need to be able to tell "we couldn't read this" apart from "this points
 * outside the workspace" apart from "this path names a directory/FIFO/socket", rather than
 * being handed one undifferentiated "something was dropped" bit.
 */
sealed interface WorkspaceFileRead {
    /** The file existed, was a regular file, resolved inside the workspace, and was read. */
    data class Present(val sha256: String, val bytes: ByteArray?) : WorkspaceFileRead

    /** The path genuinely does not exist. The ONLY classification that may seal `missing`. */
    data object Missing : WorkspaceFileRead

    /**
     * Existence is known-true or undetermined; the bytes could not be read (permissions,
     * an I/O error, a race between listing and reading). Never a claim that the file is
     * absent — the file may be sitting right there.
     */
    data object Unreadable : WorkspaceFileRead

    /**
     * The path resolves outside the workspace root, or its containment could not be
     * determined without opening it. Overwhelmingly a student's innocent
     * `ln -s ~/shared/data.csv data.csv`, not an attack — but we cannot vouch for where it
     * points, so its bytes are never read and never sealed.
     */
    data object OutOfWorkspace : WorkspaceFileRead

    /** A directory, FIFO, socket, or device sits at a path a regular file was expected. */
    data object NonRegular : WorkspaceFileRead
}

/**
 * Read+classify [relPath] (workspace-relative) for a seal.
 *
 * @param workspaceRootReal the workspace root's REAL path ([Path.toRealPath]), precomputed
 *   ONCE per seal by the caller — never per file. Realpathing only the candidate against a
 *   LEXICAL root would reject every path in an ordinary workspace whose root itself sits
 *   behind a symlink (macOS `/var` -> `/private/var` does exactly this), so both sides of
 *   the containment check must be real paths.
 * @param includeBytes whether [WorkspaceFileRead.Present] carries the raw bytes. The
 *   classic seal's ZIP step needs them; the rolling seal only needs the hash.
 */
fun readWorkspaceFile(
    workspaceRoot: Path,
    workspaceRootReal: Path,
    relPath: String,
    includeBytes: Boolean,
): WorkspaceFileRead {
    val candidate = workspaceRoot.resolve(relPath)

    // Step 1: containment. Both sides are real paths (see the [workspaceRootReal] doc above).
    //
    // Fail closed: if the candidate's toRealPath() throws, do not open the path — classify
    // exactly as the read itself would have. NoSuchFileException means the same "genuinely
    // absent" verdict Files.readAllBytes would reach; anything else means "could not
    // determine", i.e. unreadable. That equivalence is lossless because both calls walk the
    // same path through the same permission checks.
    val candidateReal =
        try {
            candidate.toRealPath()
        } catch (e: NoSuchFileException) {
            return WorkspaceFileRead.Missing
        } catch (e: Exception) {
            return WorkspaceFileRead.Unreadable
        }
    if (!candidateReal.startsWith(workspaceRootReal)) {
        return WorkspaceFileRead.OutOfWorkspace
    }

    // Step 2: regular-file gate. Two jobs, both load-bearing:
    //   (a) routes a directory-named entry — the ordinary staff typo `src` instead of
    //       `src/`, the likeliest recurrence of this whole bug class — to DROPPED rather
    //       than `missing`, which is the invariant this module exists to enforce; and
    //   (b) stops a FIFO from hanging the seal forever: Files.readAllBytes on a named pipe
    //       blocks indefinitely, and Files.isRegularFile returns false for one without
    //       opening it.
    if (!Files.isRegularFile(candidate)) {
        return WorkspaceFileRead.NonRegular
    }

    // Step 3: read. Deliberately catching only Exception, not Throwable — matches the
    // policy this replaces: a filesystem Error must not be classified at all here, it must
    // fail the seal loudly (rethrowIfFatal at the caller's enclosing handler), never come
    // back as a per-file verdict of any kind.
    return try {
        val bytes = Files.readAllBytes(candidate)
        WorkspaceFileRead.Present(Sha256.hex(bytes), if (includeBytes) bytes else null)
    } catch (e: NoSuchFileException) {
        WorkspaceFileRead.Missing
    } catch (e: Exception) {
        WorkspaceFileRead.Unreadable
    }
}
