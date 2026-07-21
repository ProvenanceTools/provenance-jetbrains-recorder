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
}
