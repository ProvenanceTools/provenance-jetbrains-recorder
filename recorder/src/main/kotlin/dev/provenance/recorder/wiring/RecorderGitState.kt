package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.GitEventPayload
import java.nio.file.Path

/**
 * Project-scoped emit seam for git.event. [repoRoot] is the Git4Idea repository's working-tree
 * root, used by the installed router to find the owning session by nearest-ancestor; null
 * routes to no owner (dropped).
 */
@Service(Service.Level.PROJECT)
class RecorderGitState {
    @Volatile
    var emit: ((repoRoot: Path?, GitEventPayload) -> Unit)? = null

    /**
     * SYNCHRONOUS fs.external_change suppression, called at the instant of the repository
     * state change — before the commit-graph read that [emit] now waits on.
     *
     * Reading `parents` is a blocking VCS call, so emission moved off the state-change
     * thread (program spec S5). The explanation tag must NOT move with it: a checkout's file
     * writes land immediately, so deferring the mark behind that read would reintroduce
     * exactly the unexplained-external-edit false positives the tagger exists to prevent.
     */
    @Volatile
    var markGit: ((repoRoot: Path?) -> Unit)? = null
}
