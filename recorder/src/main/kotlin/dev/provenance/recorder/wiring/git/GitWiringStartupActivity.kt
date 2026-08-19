package dev.provenance.recorder.wiring.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.recorder.wiring.RecorderGitState
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.TimeUnit

/**
 * git.event wiring. Registered ONLY via provjet-git.xml's optional <depends> on Git4Idea
 * (plugin id literally "Git4Idea"). This class references Git4Idea types directly
 * (git4idea.repo.*, git4idea.history.*), so it must never be reachable from the main
 * plugin.xml's always-loaded extensions — a static reference to an absent plugin's type
 * throws NoClassDefFoundError at class-verify time, regardless of any runtime
 * isPluginInstalled guard. If Git4Idea is absent, provjet-git.xml is never loaded, this
 * class is never classloaded, and git.event is simply never emitted for that session. That
 * is the graceful-degradation requirement (design.md §4) satisfied structurally, not by a
 * runtime check.
 *
 * ## What is captured, and what is deliberately not
 *
 * `sha`, `parents`, and `branch` (program spec S5) — the SHAPE of the history, so replay can
 * show branch and merge structure. **No author name, no author email, no author date, no
 * commit message**, here or anywhere else in the log. The approved CPHS protocol treats a
 * new category of identifier as requiring a filed modification BEFORE implementation, and
 * git author identity is exactly that.
 *
 * That is enforced structurally: the commit is read through [GitCommitView], which declares
 * only `sha` and `parents`, so the author fields on git4idea's `VcsCommitMetadata` are
 * unreachable rather than merely unused. [buildGitEventPayload] never sees a git4idea type
 * at all. Widening the capture would require widening that type — a visible, reviewable
 * change rather than one more line in a payload builder.
 *
 * ## Threading
 *
 * `sha` and `branch` are readable synchronously off the repository, but `parents` needs
 * [GitHistoryUtils.collectCommitsMetadata], which shells out to git. Two consequences:
 *
 *  - **Emission is serialized through a single-threaded executor**, so a fast graph read can
 *    never overtake a slow one and write log entries out of order. Log writes are ordered
 *    (CLAUDE.md).
 *  - **`markGit()` stays SYNCHRONOUS**, on the state-change thread, before anything is
 *    queued. The tagger suppresses `fs.external_change` for files a checkout rewrote, and
 *    those writes land immediately — deferring the mark behind the graph read would
 *    reintroduce precisely the false positives it exists to prevent.
 */
class GitWiringStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val state = project.service<RecorderGitState>()

        // Bounded to ONE thread: that is what keeps emission ordered. Shut down with the
        // project so there is an explicit teardown path (CLAUDE.md: no background task
        // without one).
        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
            "Provenance Git Graph",
            1,
        )
        val shutdown = Disposable {
            executor.shutdown()
            runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
        }
        Disposer.register(project, shutdown)

        val connection = project.messageBus.connect(shutdown)
        connection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                // --- Synchronous part. Everything here happens on the state-change thread.
                val repoRoot = runCatching { repository.root.toNioPath() }.getOrNull()
                val sha = runCatching { repository.currentRevision }.getOrNull()
                // Null on detached HEAD. Never invented: an omitted branch and a branch
                // literally named "HEAD" are different claims.
                val branch = runCatching { repository.currentBranchName }.getOrNull()

                // Before the queue, not after — see the class docstring.
                state.markGit?.invoke(repoRoot)

                val emit = state.emit ?: return@GitRepositoryChangeListener

                // --- Async part, queued so emission stays ordered.
                executor.execute {
                    val payload = buildGitEventPayload(
                        operation = "state_change",
                        sha = sha,
                        branch = branch,
                        reader = commitGraphReader(project, repository),
                    )
                    // Re-read the seam: the session may have ended while this was queued,
                    // in which case there is nothing to append to.
                    (state.emit ?: emit).invoke(repoRoot, payload)
                }
            },
        )
    }

    /**
     * Read one commit's parents through git4idea, projected immediately into [GitCommitView].
     *
     * `collectCommitsMetadata` returns `VcsCommitMetadata`, which also exposes the author and
     * the full message. Nothing but `parents` is read off it, and the projection happens here
     * so no git4idea commit object escapes into the payload path.
     */
    private fun commitGraphReader(project: Project, repository: GitRepository): GitCommitGraphReader =
        GitCommitGraphReader { sha ->
            try {
                val metadata = GitHistoryUtils
                    .collectCommitsMetadata(project, repository.root, sha)
                    ?.firstOrNull()
                    ?: return@GitCommitGraphReader null
                GitCommitView(
                    sha = sha,
                    // ONLY parents. Order preserved exactly as git reports it.
                    parents = metadata.parents.map { it.asString() },
                )
            } catch (e: Exception) {
                // A shallow clone, a corrupt object, a repository closing underneath us.
                // Unknown parents are OMITTED rather than reported as an empty list.
                LOG.debug("provenance: could not read commit parents for $sha", e)
                null
            }
        }

    private companion object {
        private val LOG = Logger.getInstance(GitWiringStartupActivity::class.java)
    }
}
