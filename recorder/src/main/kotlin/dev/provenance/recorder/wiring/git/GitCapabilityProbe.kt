package dev.provenance.recorder.wiring.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.GitCaptureCapability
import dev.provenance.recorder.wiring.sameAncestryLine
import java.nio.file.Path

/**
 * The `session.start.git_capture` CAPABILITY REPORT (collaboration spec §5.6 item 2), write
 * side, D16.
 *
 * ## This module is deliberately reachable from the ALWAYS-loaded plugin.xml
 *
 * Every other file in `wiring/git/` ([GitWiringStartupActivity], [RootCommitSha]) references
 * Git4Idea types directly and is loaded ONLY via `provjet-git.xml`'s optional `<depends>` —
 * referencing an absent plugin's type throws `NoClassDefFoundError` at class-verify time,
 * regardless of any runtime "is it installed" guard (see [GitWiringStartupActivity]'s
 * docstring). [probeGitCapture] is called from [dev.provenance.recorder.session.RecordingSessionController],
 * which IS reachable from the main, always-loaded `plugin.xml`, so this file — unlike its
 * siblings — must import NOTHING from `git4idea.*`. Every git4idea type this file touches
 * ([isClassLoadable], [reflectiveGitRepositoryRoots]) is reached by [Class.forName] /
 * [java.lang.reflect.Method], never a static import, which is what makes that possible: this
 * file's own bytecode never mentions a Git4Idea class, so it is safe to load unconditionally.
 * [Project] and [VirtualFile] are core-platform types (`com.intellij.openapi.*`), not
 * Git4Idea's — referencing them here carries none of that risk.
 *
 * ## `NOT_OWNED`, reachable via a reflective repository enumeration
 *
 * log-core's `git_capture` has three answers (see `GitCaptureCapability`'s KDoc), and the VS
 * Code recorder computes all three synchronously off `vscode.git`'s `repositories` getter. On
 * this host that repository list lives behind `git4idea.repo.GitRepositoryManager`.
 *
 * A prior pass here reported `NOT_OWNED` as needing a decision between two designs: a
 * reflective repository enumeration, or threading the session router's own ownership answer
 * through [dev.provenance.recorder.wiring.RecorderGitState] (already loaded unconditionally,
 * already the seam `git.event` routing goes through). The second is genuinely unsafe:
 * [installGitWiring] and [dev.provenance.recorder.activation.RecorderActivationActivity] (which
 * starts the session that needs the answer) are both `postStartupActivity` entries with no
 * ordering guarantee, so a value read through that seam at session-start time could easily not
 * exist yet.
 *
 * The first was rejected on the grounds that it "cannot be validated without a running IDE with
 * Git4Idea enabled." That is not so: `recorder/build.gradle.kts` already pulls Git4Idea in as a
 * `bundledPlugins` TEST dependency, and `GitExternalChangeGateTest` already drives a real
 * `GitRepositoryManager` through `BasePlatformTestCase` (init a real repo, map it, wait for
 * Git4Idea to detect it). [reflectiveGitRepositoryRoots] is validated the same way, against the
 * genuine class, in [GitCapabilityProbeIntegrationTest]. The method signatures reflected on
 * here (`GitRepositoryManager.getInstance(Project)`,
 * `.getRepositories()`, `Repository.getRoot()`) were also confirmed directly against the
 * `vcs-git.jar` bundled with this project's own `platformVersion` (2026.1.4) — decompiled with
 * `javap`, not guessed.
 *
 * ## No cross-activity race
 *
 * [probeGitCapture] does **not** read anything [GitWiringStartupActivity] or any other
 * `postStartupActivity` populates. `GitRepositoryManager` is git4idea's OWN project service; the
 * probe asks it directly, synchronously, the moment [dev.provenance.recorder.session.RecordingSessionController]'s
 * `init` block runs. The only timing caveat is the one the VS Code implementation already
 * documents for its own snapshot: git4idea's own VCS-root detection may not have finished
 * mapping every repository the instant a project opens, so a repository that appears moments
 * later is not seen by this call. That cannot manufacture a WRONG answer, only a stale one in
 * the conservative direction — an empty (or incomplete) repository list reads as
 * [GitCaptureCapability.AVAILABLE] (see `decideGitCapture`'s "zero repositories" rule below),
 * never as [GitCaptureCapability.NOT_OWNED]. That is the same "wait and see" answer this probe
 * already gave for every git4idea-enabled session before this file changed.
 *
 * ## Why `NOT_OWNED` is actually reachable here, not merely theoretical
 *
 * [dev.provenance.recorder.session.RecorderSessionManager] supports N **concurrently active**
 * sessions in one project — nested-manifest discovery finds one verified assignment root per
 * `.provenance-manifest`, and each gets its own [dev.provenance.recorder.session.RecordingSessionController].
 * `git.event` is routed to the owning session by nearest-ancestor of the git repository's own
 * root ([RecorderGitState]'s KDoc: "used by the installed router to find the owning session by
 * nearest-ancestor; null routes to no owner (dropped)"). IntelliJ auto-detects every nested
 * `.git` directory under a project as its own VCS mapping — unlike VS Code, this needs no
 * multi-root workspace to be true even of a single content root — so a project with two
 * sibling assignment directories (e.g. a `61b/hw1/` with its own repo and a `61c/hw2/` that is
 * NOT under version control, both concurrently recording) is a real, unremarkable shape: for
 * the `61c/hw2/` session, `GitRepositoryManager.getRepositories()` answers non-empty (it sees
 * `61b/hw1`'s repository), Git4Idea is plainly available, and yet nothing that session's own
 * router will ever route reaches it — exactly [GitCaptureCapability.NOT_OWNED]'s definition:
 * "git observation worked, and every repository visible to it was outside this session's
 * assignment scope."
 *
 * [decideGitCapture] mirrors [RecorderSessionManager]'s own ownership predicate — via the shared
 * [sameAncestryLine] — in BOTH directions a repository root can relate to the session root: at-
 * or-below it (`repoRoot.startsWith(sessionRoot)`, the ordinary/submodule case), or ABOVE it
 * (`sessionRoot.startsWith(repoRoot)`, one shared class repo with the assignment as a
 * subdirectory — the "standard nested layout" the monorepo's VS Code recorder had a routing bug
 * for: decision-log bug 3). [RecorderSessionManager.sessionsOwningRepo] now routes `git.event`s
 * to every session in that second direction too (not just the nearest, since several sibling
 * assignments under one shared repo can all be recording at once), so this probe answers
 * AVAILABLE for it as well — reporting NOT_OWNED here while the router actually delivers the
 * event would be exactly the capability-report-contradicts-the-wiring failure this report exists
 * to prevent. What this probe still cannot do, because it has no visibility into sibling session
 * roots (see [probeGitCapture]'s single `sessionRoot` argument): the router's nearest-ancestor
 * disambiguation BETWEEN sibling sessions when a repository sits at-or-below more than one of
 * them. That is a pre-existing, narrower gap (this session-local snapshot was always coarser than
 * the live router in that one respect) and not one this change introduces or widens.
 */
fun probeGitCapture(
    project: Project,
    sessionRoot: Path,
    git4IdeaClassLoadable: () -> Boolean = ::isGit4IdeaClassLoadable,
    repositoryRootsOf: (Project) -> List<Path>? = ::reflectiveGitRepositoryRoots,
): GitCaptureCapability? {
    if (!git4IdeaClassLoadable()) return GitCaptureCapability.UNAVAILABLE
    // null means the reflective probe itself failed (a method/field mismatch on some IDE's
    // Git4Idea build) rather than "zero repositories" — those are different facts, and only
    // the latter is an answer this session is entitled to give. Guessing is worse than
    // silence: omit rather than report a capability this probe could not actually establish.
    val repositoryRoots = repositoryRootsOf(project) ?: return null
    return decideGitCapture(sessionRoot, repositoryRoots)
}

/**
 * The pure ownership decision, isolated from IntelliJ/Git4Idea entirely so it is testable with
 * plain [Path]s.
 *
 * Mirrors [dev.provenance.recorder.session.RecorderSessionManager]'s own
 * `sessionsOwningRepo` predicate: a repository is owned by this session iff it sits on the same
 * ancestry line as [sessionRoot] — at-or-below it (submodule / ordinary case) OR above it (the
 * shared-class-repo layout, decision-log bug 3's fix) — via the shared [sameAncestryLine].
 * **Zero repositories is [GitCaptureCapability.AVAILABLE], not [GitCaptureCapability.NOT_OWNED]**
 * — an assignment with no git repository (yet) has nothing to route away from, and the live
 * `GitRepositoryChangeListener` subscription would pick one up if it appeared later. `NOT_OWNED`
 * is reserved for "at least one repository exists, and none of them is this session's".
 */
internal fun decideGitCapture(sessionRoot: Path, repositoryRoots: List<Path>): GitCaptureCapability {
    if (repositoryRoots.isEmpty()) return GitCaptureCapability.AVAILABLE
    val normalizedSessionRoot = sessionRoot.normalize()
    val owned = repositoryRoots.any { repoRoot ->
        val normalizedRepoRoot = runCatching { repoRoot.toRealPath() }.getOrDefault(repoRoot.normalize())
        sameAncestryLine(normalizedRepoRoot, normalizedSessionRoot)
    }
    return if (owned) GitCaptureCapability.AVAILABLE else GitCaptureCapability.NOT_OWNED
}

/** [probeGitCapture]'s production check: is `GitRepositoryManager` loadable on this classpath? */
internal fun isGit4IdeaClassLoadable(): Boolean = isClassLoadable("git4idea.repo.GitRepositoryManager")

/**
 * True iff [className] can be loaded on this classpath, false on any failure to do so.
 *
 * Reflective and total: [Class.forName] takes a [String], so THIS FILE's own bytecode never
 * references a Git4Idea type, which is what makes it safe to call from code loaded
 * unconditionally by the main `plugin.xml` — see the file docstring. `Throwable` and not
 * `Exception`, because a `LinkageError` from a half-initialized plugin classloader must cost
 * this one capability report rather than take the session down with it; only
 * [VirtualMachineError] propagates, since nothing sensible survives an `OutOfMemoryError`.
 *
 * `internal` and parameterised by class name (rather than hard-coded) purely for testability: a
 * unit test can prove the reflective probe itself distinguishes a real class from a made-up one,
 * without needing Git4Idea to be genuinely absent from the test classpath.
 */
internal fun isClassLoadable(className: String): Boolean =
    try {
        Class.forName(className)
        true
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        false
    }

/**
 * Every currently known Git4Idea repository's working-tree root, or `null` if the reflective
 * probe itself could not answer.
 *
 * Purely reflective ([Class.forName] + [java.lang.reflect.Method], never a static git4idea
 * import) for the same class-loading-safety reason as [isClassLoadable] — see the file
 * docstring. Only called after [isGit4IdeaClassLoadable] has already confirmed
 * `GitRepositoryManager` itself is loadable, so a failure here is a genuine method/field
 * mismatch (a Git4Idea build this reflection was not written against), not class absence.
 *
 * The three reflected calls — `GitRepositoryManager.getInstance(Project)`,
 * `GitRepositoryManager.getRepositories()`, `Repository.getRoot()` (inherited by every
 * `GitRepository` instance, resolved via the repository's own runtime class rather than a
 * static `Repository` reference) — were confirmed against the `vcs-git.jar` bundled with this
 * project's own `platformVersion` (`javap git4idea/repo/GitRepositoryManager.class` /
 * `GitRepository.class`), not merely assumed.
 *
 * `null` on ANY failure — a missing method, an unexpected return type, an exception from
 * git4idea itself — never a partial list. A single malformed entry is rare enough that folding
 * it into "the whole probe could not answer" (which [probeGitCapture] turns into an omitted
 * field) is simpler and safer than partially trusting a reflective read that already surprised
 * us once.
 */
internal fun reflectiveGitRepositoryRoots(project: Project): List<Path>? =
    try {
        val managerClass = Class.forName("git4idea.repo.GitRepositoryManager")
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        if (manager == null) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            val repositories = managerClass.getMethod("getRepositories").invoke(manager) as List<Any>
            repositories.map { repo ->
                val root = repo.javaClass.getMethod("getRoot").invoke(repo) as VirtualFile
                root.toNioPath()
            }
        }
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        LOG.debug("provenance: reflective GitRepositoryManager probe failed", t)
        null
    }

private val LOG = Logger.getInstance("dev.provenance.recorder.wiring.git.GitCapabilityProbe")
