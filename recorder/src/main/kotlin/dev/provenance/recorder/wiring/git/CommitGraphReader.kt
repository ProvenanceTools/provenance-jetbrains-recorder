package dev.provenance.recorder.wiring.git

/**
 * The ONLY view of a git commit this plugin is allowed to have (program spec S5).
 *
 * ## Why this type exists at all
 *
 * A git4idea commit object also carries the author's real name, their email address, the
 * author and commit dates, and the full commit message. **None of those may be recorded**,
 * here or anywhere else in the log. The approved CPHS protocol treats a new category of
 * identifier as requiring a filed modification BEFORE implementation, and git author
 * identity is exactly that — a real name and a real email, in clear, attached to every
 * commit. `sha`, `parents`, and `branch` are structural: they describe the SHAPE of the
 * history, not who produced it. Attribution already has a designed, opaque home —
 * `student_ref` inside `session.start.identity`.
 *
 * The constraint is enforced STRUCTURALLY rather than by discipline. [GitCommitView] declares
 * `sha` and `parents` and nothing else, and the reader below is the only path from a git4idea
 * object into the recorder. Author fields are therefore UNREACHABLE rather than merely
 * unused: adding one would require widening this type, which is a visible, reviewable change
 * rather than an easy line in a payload builder.
 *
 * `GitCommitGraphReader` is a function type so the wiring can be unit-tested without a live
 * repository — and so the test can feed a commit object that DOES carry author fields and
 * prove none of them reach the emitted payload.
 */
data class GitCommitView(
    /** Full 40-char hex sha, or null when it could not be read. */
    val sha: String?,
    /**
     * Parent shas in git's own order — first parent is the branch merged into.
     *
     * Null means "could not read the parents", which is NOT the same as an empty list
     * meaning "this commit genuinely has none". The distinction survives all the way to the
     * wire; see `GitEventPayload.parents`.
     */
    val parents: List<String>?,
)

/**
 * Resolve one commit's graph position by sha. Returns null when the commit cannot be read at
 * all — a shallow clone, a corrupt object, a repository closing underneath us.
 *
 * Reading parents requires git4idea's history API, which is a blocking VCS call and must not
 * run on the EDT, so implementations are invoked from the emission path off the UI thread.
 */
fun interface GitCommitGraphReader {
    fun read(sha: String): GitCommitView?
}
