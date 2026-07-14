package dev.provenance.recorder.wiring.git

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.provenance.core.GitEventPayload
import dev.provenance.recorder.wiring.RecorderGitState
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * git.event wiring. Registered ONLY via provjet-git.xml's optional <depends> on Git4Idea
 * (plugin id literally "Git4Idea"). This class references Git4Idea types directly
 * (git4idea.repo.*), so it must never be reachable from the main plugin.xml's
 * always-loaded extensions — a static reference to an absent plugin's type throws
 * NoClassDefFoundError at class-verify time, regardless of any runtime isPluginInstalled
 * guard. If Git4Idea is absent, provjet-git.xml is never loaded, this class is never
 * classloaded, and git.event is simply never emitted for that session. That is the
 * graceful-degradation requirement (design.md §4: "git ... must degrade gracefully if
 * Git4Idea absent") satisfied structurally, not by a runtime check.
 *
 * Mirrors packages/recorder/src/wiring/git-wiring.ts's single behavior: on any repository
 * state change, emit git.event with operation "state_change" and the current HEAD sha if
 * available. Simpler than the VS Code port: GIT_REPO_CHANGE is a project-wide topic that
 * fires for every repository (present and future), so there is no separate
 * onDidOpenRepository/onDidCloseRepository tracking to port — one subscription covers all.
 *
 * The emit seam is the project-scoped [RecorderGitState] (core-only types, main-path
 * safe), null until a session activates. NOTE (pending the external-change plan): the VS
 * Code git-wiring.ts marks a git-explanation tag here to suppress fs.external_change false
 * positives from checkout/reset rewriting files (PRD §4.5). That tagger is not wired in
 * this plan; when it lands, mark it from this exact point.
 */
class GitWiringStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val state = project.service<RecorderGitState>()
        project.messageBus.connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                val emit = state.emit ?: return@GitRepositoryChangeListener
                emit(GitEventPayload(operation = "state_change", commitSha = repository.currentRevision))
            },
        )
    }
}
