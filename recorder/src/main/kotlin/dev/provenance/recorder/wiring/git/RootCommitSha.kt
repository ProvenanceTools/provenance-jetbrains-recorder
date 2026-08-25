package dev.provenance.recorder.wiring.git

import dev.provenance.core.REPOSITORY_DISCRIMINATOR_FIELD
import dev.provenance.core.RepositoryDiscriminatorRead
import dev.provenance.core.readRepositoryDiscriminator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE REPOSITORY DISCRIMINATOR, write side (decision D12, collaboration spec S14(b)).
 *
 * A scope can observe more than one repository: a submodule, or a repository nested inside the
 * one that owns the assignment root. Their sha spaces are unrelated, so a reader that keys
 * observed commits by sha alone merges two graphs that have nothing to do with each other.
 * `git.event.root_commit_sha` is what lets the analyzer key on `(repository, sha)` for real.
 *
 * The reader half is `core`'s [readRepositoryDiscriminator]. The writer contract this file
 * implements is pinned in the monorepo's
 * `docs/superpowers/specs/2026-08-19-program-decision-log.md`, "The writer contract for
 * `root_commit_sha`". The reader is correct whatever the writer picks — the value is opaque and
 * compared only for equality — so the contract exists to make three recorders derive the SAME
 * value, which is the only thing that makes correlation work at all.
 *
 * ## The value
 *
 * `git rev-list --max-parents=0 --first-parent HEAD` — the root of HEAD's FIRST-PARENT lineage.
 * First-parent because that lineage stays on the mainline when an imported history is merged
 * in, which is what keeps two partners agreeing. If it yields more than one root — an orphan
 * branch, a squashed import; ORDINARY, and never a finding — the lexicographically smallest is
 * taken, so two partners with the same history agree.
 *
 * ## Absence is a legal, permanent, blameless answer
 *
 * The field is OMITTED — never `null` — when the repository is shallow, when git fails, times
 * out, or is missing, and when the value that comes back is not a commit sha. Omission and
 * `null` canonicalize differently and therefore chain to different hashes; readers accept
 * `null` as absence so a nonconforming log still parses, but a writer that emits it is
 * nonconforming. [GitEventPayload.rootCommitSha] cannot express `null`, which is how that is
 * enforced structurally rather than by care here.
 *
 * A **shallow clone** is the case worth naming: its boundary commit has no parents and is NOT a
 * root, so emitting it would publish a value a full clone of the same repository disagrees with
 * — a silent failure to correlate, dressed as a successful one. `--is-shallow-repository` is
 * checked first and anything other than a definite `false` omits. That flag needs git ≥ 2.15;
 * an older git errors out, which lands in "omit on any failure", so the version is handled by
 * the general rule rather than by a special case.
 *
 * ## Why the shape check goes through `core`'s READER
 *
 * [isUsableDiscriminator] calls [readRepositoryDiscriminator] rather than restating its regex.
 * One narrowing, four consumers — the monorepo's analyzer and server through `analysis-core`,
 * plus this repo and provnvim. A writer that shape-checked separately could emit a value its own
 * reader rejects, and a rejected value is a repository the partners silently fail to correlate
 * on. Running the reader on the WRITE side means a repository path or a remote URL is never
 * written down at all — S14(b), and the reason the reader shape-checks in the first place.
 *
 * ## No identity, and no network (PRD NG2)
 *
 * `rev-parse` and `rev-list` are local object-database reads. Nothing here fetches, nothing
 * consults a remote, and no author name, author email or commit message is read — the two
 * commands cannot surface them.
 */

/**
 * The seam onto git plumbing. Injected so the derivation rules are unit-testable without a
 * repository, and so every failure mode (non-zero exit, missing binary, garbage output) is
 * expressible as a thrown exception in a test.
 *
 * The production implementation goes through the **IntelliJ VCS API**
 * (`git4idea.commands.Git`), not a raw process spawn — see `gitPlumbing` in
 * [GitWiringStartupActivity].
 *
 * Implementations return stdout as lines and THROW on any failure; [deriveRootCommitSha]
 * converts every throw into an omission.
 */
fun interface GitPlumbing {
    /**
     * Run one read-only git plumbing command in [repoRoot] and return its stdout lines.
     *
     * [command] is the subcommand name (`"rev-parse"`, `"rev-list"`) and [args] its fixed
     * arguments. Never a shell string: the two are kept apart so nothing here can be
     * concatenated into a command line.
     */
    fun run(repoRoot: Path, command: String, args: List<String>): List<String>
}

/**
 * True iff [value] is what every reader accepts as a repository discriminator.
 *
 * Deliberately delegates to `core`'s reader rather than restating its regex — see the file
 * docstring. A rename of the field is a compile error here rather than a silent
 * cross-repository disagreement.
 */
private fun isUsableDiscriminator(value: String): Boolean =
    readRepositoryDiscriminator(
        buildJsonObject { put(REPOSITORY_DISCRIMINATOR_FIELD, value) },
    ) is RepositoryDiscriminatorRead.Recorded

/**
 * Derive the repository discriminator for ONE repository, or `null` to OMIT the field.
 *
 * Called ONCE per repository (see [RepositoryDiscriminators]) — never per event. It cannot
 * change for the life of a repository, and the event path must not pay for it.
 *
 * Never throws: every failure is an omission. `Throwable` and not `Exception`, because a
 * `LinkageError` from an IDE without the git plumbing this expects must cost witnessing rather
 * than recording; only [VirtualMachineError] propagates, since nothing sensible survives an
 * `OutOfMemoryError`.
 */
fun deriveRootCommitSha(repoRoot: Path, git: GitPlumbing): String? {
    try {
        // A shallow clone's boundary commit has no parents and is NOT a root. Anything but a
        // definite `false` omits — including a git too old to know the flag, which errors out
        // and lands in the catch below.
        val shallow = git.run(repoRoot, "rev-parse", listOf("--is-shallow-repository"))
            .firstOrNull()
            ?.trim()
        if (shallow != "false") return null

        val roots = git
            .run(repoRoot, "rev-list", listOf("--max-parents=0", "--first-parent", "HEAD"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Validated through the READER, so a value this recorder's own analyzer would
            // reject is never written into a signed chain.
            .filter(::isUsableDiscriminator)

        // Several roots is ORDINARY and never a finding. Lexicographically smallest is
        // deterministic, which is what makes two partners with the same history agree.
        return roots.minOrNull()
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        // git missing, not a repository, an empty repository (`HEAD` does not resolve),
        // permission denied, a timeout — all the same answer. Guessing is worse than silence,
        // and silence costs only correlation.
        return null
    }
}

/**
 * The per-repository memo. **Writer rule 1: derive once per repository, never per event.**
 *
 * Keyed on the repository's own working-tree root, and derived with that root as the working
 * directory, which is **writer rule 9**: a session that observes a submodule as well as its
 * outer repository labels each event with its OWN repository's root. Labelling a submodule
 * event with the outer repository's root re-creates the exact sha-space merge this field exists
 * to prevent — so one memo entry per root, and the root is both the cache key and the cwd.
 *
 * `computeIfAbsent` is the derivation guarantee: even if two repository state changes for one
 * root raced, git runs once. [derivations] exists so a test can assert that.
 *
 * A `null` result is MEMOIZED too (as an empty [Optional]). Absence is a permanent answer for a
 * shallow clone, so re-deriving it on every event would spend a git invocation per event to
 * learn the same "no" — precisely the per-event cost rule 1 forbids.
 */
class RepositoryDiscriminators(private val derive: (Path) -> String?) {
    private val cache = ConcurrentHashMap<Path, Optional<String>>()
    private val derivations = AtomicInteger(0)

    /**
     * The discriminator for [repoRoot], deriving it on first sight and memoizing thereafter.
     *
     * `null` for a null root: an event this recorder cannot attribute to a repository root is
     * one the session router drops anyway, and running git for it would be work done for an
     * observation that is never recorded.
     */
    fun of(repoRoot: Path?): String? {
        if (repoRoot == null) return null
        return cache.computeIfAbsent(repoRoot) {
            derivations.incrementAndGet()
            Optional.ofNullable(derive(it))
        }.orElse(null)
    }

    /** How many times the underlying derivation actually ran. For tests only. */
    fun derivationCount(): Int = derivations.get()
}
