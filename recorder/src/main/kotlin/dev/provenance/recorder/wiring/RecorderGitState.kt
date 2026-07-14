package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.GitEventPayload

/**
 * Project-scoped emit seam for git.event, mirroring RecorderPasteState's pattern.
 *
 * This service references ONLY core payload types (no Git4Idea types), so it is safe on
 * the main plugin.xml load path — it loads even on IDEs without Git4Idea. The gated
 * GitWiringStartupActivity (registered only via provjet-git.xml, and the only class that
 * references Git4Idea types) resolves this service and reads [emit] on each repository
 * change: null until a session activates (privacy gate), and while null nothing is
 * recorded. The final integration pass sets [emit] on session start and clears it on
 * session end — this plan wires the seam but does not touch the controller.
 */
@Service(Service.Level.PROJECT)
class RecorderGitState {
    @Volatile
    var emit: ((GitEventPayload) -> Unit)? = null
}
