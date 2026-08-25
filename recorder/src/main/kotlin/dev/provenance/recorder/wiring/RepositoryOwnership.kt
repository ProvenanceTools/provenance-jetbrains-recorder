package dev.provenance.recorder.wiring

import java.nio.file.Path

/**
 * True iff [a] and [b] sit on the same branch of the filesystem tree — one of them is an
 * ancestor of the other, in EITHER direction (equality counts as "ancestor of itself").
 *
 * This is the building block behind git.event ownership (decision-log bug 3, monorepo
 * `docs/superpowers/specs/2026-08-19-program-decision-log.md`: "Every `git.event` was silently
 * discarded on nested layouts. The ownership gate used a containment predicate written for
 * files, handed a git repo root that sits ABOVE the assignment root."). A git repository root is
 * normally an ANCESTOR of the assignment root it serves in the standard shared-class-repo
 * layout — one repo, one `.provenance-manifest` per assignment subdirectory beneath it — not a
 * descendant of it or the same directory, which is all a plain one-directional containment check
 * (`b.startsWith(a)`) can express.
 *
 * [dev.provenance.recorder.session.RecorderSessionManager]'s live git.event router and
 * [dev.provenance.recorder.wiring.git.GitCapabilityProbe]'s `decideGitCapture` capability report
 * both build on this single function so the two can never independently drift on which direction
 * counts as "owned" — see each call site's KDoc for how the surrounding logic differs (the
 * router additionally disambiguates BETWEEN sibling sessions with a nearest-ancestor rule when
 * several could claim a repository at-or-below them; the probe has no visibility into sibling
 * session roots at all, so it answers the coarser, session-local question this function poses
 * directly).
 */
fun sameAncestryLine(a: Path, b: Path): Boolean = a.startsWith(b) || b.startsWith(a)
