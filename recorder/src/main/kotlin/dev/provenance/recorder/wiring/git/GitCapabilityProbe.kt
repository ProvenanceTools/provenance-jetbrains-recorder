package dev.provenance.recorder.wiring.git

import dev.provenance.core.GitCaptureCapability

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
 * siblings — must import NOTHING from `git4idea.*`. [isClassLoadable] is a purely reflective
 * probe (`Class.forName` takes a `String`, never a type reference), which is what makes that
 * possible: this file's own bytecode never mentions a Git4Idea class, so it is safe to load
 * unconditionally.
 *
 * ## Scope: `available` / `unavailable` only — `NOT_OWNED` is not derived here
 *
 * log-core's `git_capture` has three answers (see `GitCaptureCapability`'s KDoc), and the VS
 * Code recorder computes all three synchronously off `vscode.git`'s `repositories` getter. On
 * this host that repository list lives behind `git4idea.repo.GitRepositoryManager`, and reaching
 * it safely from here would need either:
 *
 *  1. a REFLECTIVE call through `GitRepositoryManager.getInstance(project).repositories` (no
 *     static type reference, so classloading-safe) — not attempted here because it cannot be
 *     validated without a running IDE with Git4Idea enabled, and a wrong method/field name would
 *     fail silently (an exception this probe would have to swallow into `UNAVAILABLE`, exactly
 *     as it would for the genuinely-absent case, so a broken reflective probe and a real
 *     "no Git4Idea" would be indistinguishable in the field); or
 *  2. threading the current session's ownership predicate through
 *     [dev.provenance.recorder.wiring.RecorderGitState] (already loaded unconditionally, already
 *     the seam `git.event` routing goes through) and having [installGitWiring] populate it — but
 *     that activity and [dev.provenance.recorder.activation.RecorderActivationActivity] (which
 *     starts the session that needs the answer) are BOTH `postStartupActivity` entries with no
 *     ordering guarantee between them, so the value session.start would read could easily not
 *     exist yet on the very first project open.
 *
 * Both are real, buildable designs; neither is a small, obviously-correct addition on top of the
 * existing D12 (`root_commit_sha`) plumbing, which never had this problem because it labels
 * `git.event` payloads emitted asynchronously by [GitWiringStartupActivity] itself, never a
 * value session.start needs synchronously at construction time. Rather than invent one of the
 * two under this task, this probe answers only what a single, safe, race-free check can answer:
 * whether Git4Idea is on the classpath at all. That is exactly the [GitCaptureCapability.UNAVAILABLE]
 * distinction — "nothing this session did could have produced a `git.event`" — which is also the
 * one D16 cites as the missing context for `git_unrecorded_in`. `NOT_OWNED` (git worked, but no
 * repository was in scope) is never reported by this recorder today; every git4idea-enabled
 * session reports [GitCaptureCapability.AVAILABLE], which is the same "wait and see" answer a
 * `git.event`-free session already gave before this field existed. Extending this to `NOT_OWNED`
 * is a follow-up, not a silent gap: it needs one of the two designs above, decided and reviewed
 * on its own.
 */
fun probeGitCapture(git4IdeaClassLoadable: () -> Boolean = ::isGit4IdeaClassLoadable): GitCaptureCapability =
    if (git4IdeaClassLoadable()) GitCaptureCapability.AVAILABLE else GitCaptureCapability.UNAVAILABLE

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
