package dev.provenance.recorder.wiring.git

import dev.provenance.core.GitEventPayload

/**
 * Assemble a `git.event` payload from a repository state change (program spec S5).
 *
 * **Pure, and deliberately free of every git4idea import** — it depends only on
 * [GitCommitView] and `core`. That makes it unit-testable without a live repository, which
 * is what lets a test feed a commit object carrying `authorName` / `authorEmail` / `message`
 * and assert that none of it reaches the payload.
 *
 * Three rules a port has to get right, each pinned by a conformance vector:
 *
 *  - **`commit_sha` is still emitted alongside `sha`.** They are the same value; the former
 *    is what 1.x readers know, and it stays through the reader-before-writer migration.
 *  - **`parents` order is never touched.** The first parent is the branch merged into, so
 *    sorting it inverts the meaning of a merge — and JCS leaves array elements alone, so a
 *    sort changes the signed bytes and the chain hash too.
 *  - **An empty parent list is not the same as an absent one.** `[]` is a positive claim
 *    ("root commit"); absent means "could not read". A read failure is not entitled to make
 *    the former claim, so [reader] returning null omits the field entirely.
 */
fun buildGitEventPayload(
    operation: String,
    sha: String?,
    /** Current branch, or null on detached HEAD. Never invented. */
    branch: String?,
    /** Resolves parents for [sha]. Null result means "unknown", not "none". */
    reader: GitCommitGraphReader?,
): GitEventPayload {
    // Only consulted when there is a sha to resolve. A repository with no HEAD (a fresh
    // `git init`) has no commit to describe, and asking for one would be a wasted VCS call.
    val parents: List<String>? = if (sha != null && reader != null) {
        runCatching { reader.read(sha) }.getOrNull()?.parents
    } else {
        null
    }

    return GitEventPayload(
        operation = operation,
        // Same value under both keys, on purpose: 1.x readers only know `commit_sha`.
        commitSha = sha,
        sha = sha,
        parents = parents,
        branch = branch,
    )
}
