package dev.provenance.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading the repository discriminator off a `git.event` payload — decision D12
 * (collaboration spec S14(b)). Ported from log-core's `git-event.ts`; the payload TYPE lives
 * in [Events.kt] with every other payload, and the runtime narrowing lives here, exactly as
 * log-core splits it.
 *
 * ## What the discriminator is, and what it is not
 *
 * It is the repository's **root-commit sha**, used for one thing: deciding whether two
 * observed commits live in the same sha space. Node identity in the analyzer's observed DAG
 * is `(repository, sha)`, and without a discriminator a scope that observed a submodule
 * alongside its outer repository merges two unrelated sha spaces into one graph.
 *
 * The root commit was chosen because **both partners on one repository derive the same value
 * offline**, which is what makes cross-contributor correlation possible at all, and because a
 * submodule has a different root commit, so it discriminates correctly.
 *
 * It is deliberately **not** the repository path (a filesystem path is arguably an identifier
 * and is certainly noisy) and **not** a remote URL (which embeds the org and frequently the
 * student's own username). That constraint is the reason this reader checks the value's
 * SHAPE, which [GitEventPayload.sha] deliberately does not.
 *
 * ## Why a WRITER calls a READER
 *
 * `dev.provenance.recorder.wiring.git.deriveRootCommitSha` validates every candidate through
 * THIS function rather than a private regex. One narrowing, four consumers — the monorepo's
 * analyzer and server through `analysis-core`, plus this repo and provnvim, which need the
 * identical rules to EMIT conformant payloads. A writer that shape-checked separately could
 * emit a value its own reader rejects, and a rejected value is a repository the partners
 * silently fail to correlate on.
 *
 * ## Absent is the ordinary case, permanently
 *
 * Every bundle recorded before D12 carries no discriminator. [RepositoryDiscriminatorRead]
 * therefore models absence as its own answer, and a reader must treat it exactly as it
 * treated the world before the field existed. **Absent is not empty, is not a defect, and can
 * never be evidence of anything.**
 */

/**
 * The payload key that carries the discriminator.
 *
 * Named through this constant rather than a repeated string literal, so a rename is a compile
 * error rather than a silent cross-repository disagreement.
 */
const val REPOSITORY_DISCRIMINATOR_FIELD: String = "root_commit_sha"

/** sha-1 (40) and sha-256 (64) object names, lowercase hex, as git prints them. */
private val ROOT_COMMIT_SHA_RE = Regex("^([0-9a-f]{40}|[0-9a-f]{64})$")

/**
 * Why a present discriminator could not be read.
 *
 * Descriptive only. None of these is evidence about a student, and no reader may turn one
 * into a finding.
 */
enum class RepositoryDiscriminatorProblem {
    /** Present, but not a JSON string. */
    NOT_A_STRING,

    /** Present and a string, but empty. An empty key would merge every repository. */
    EMPTY,

    /**
     * A non-empty string that is not a lowercase 40- or 64-hex object name. A repository
     * path, a remote URL, an abbreviated sha and an uppercased sha all land here.
     */
    NOT_A_COMMIT_SHA,
    ;

    /** The wire spelling log-core uses, so a conformance vector can be compared directly. */
    val wire: String
        get() = when (this) {
            NOT_A_STRING -> "not_a_string"
            EMPTY -> "empty"
            NOT_A_COMMIT_SHA -> "not_a_commit_sha"
        }
}

/**
 * What one `git.event` payload says about which repository it came from.
 *
 * Three answers, kept apart on purpose:
 *
 *  - [Absent] — the field is not there. The ordinary case for every bundle recorded before
 *    D12, and for a shallow clone forever. Read it as "this observation is unlabelled", never
 *    as "a different repository" and never as a defect.
 *  - [Recorded] — a usable root-commit sha.
 *  - [Malformed] — something was there and could not be used.
 *
 * [Absent] and [Malformed] lead the caller to the same place (the unlabelled repository) and
 * are still different facts, which is why they are different variants: one is a recorder that
 * had nothing to say, the other a recorder that said something wrong, and only the second is
 * worth counting.
 */
sealed interface RepositoryDiscriminatorRead {
    data object Absent : RepositoryDiscriminatorRead

    data class Recorded(val rootCommitSha: String) : RepositoryDiscriminatorRead

    data class Malformed(val problem: RepositoryDiscriminatorProblem) : RepositoryDiscriminatorRead

    /** The wire spelling of the variant, matching log-core's `kind` field. */
    val kind: String
        get() = when (this) {
            is Absent -> "absent"
            is Recorded -> "recorded"
            is Malformed -> "malformed"
        }
}

/**
 * Read the repository discriminator out of an untyped `git.event` `data` value.
 *
 * Pure and total: never throws, never mutates. A `.slog` is a student-editable file, so every
 * input here is untrusted and a malformed one is EXPECTED rather than exceptional.
 *
 * `null` is accepted as absence so a writer that spelled the unknown case explicitly still
 * reads correctly — but a **writer must OMIT the field**, never emit `null`. The two
 * canonicalize differently and therefore chain to different hashes, exactly as `parents: []`
 * and an absent `parents` do, and the format pins omission.
 *
 * A `null` payload reads as [RepositoryDiscriminatorRead.Absent]: there is no field on a
 * non-object, and manufacturing a problem out of a payload this function does not own would
 * report the same garbage twice.
 */
fun readRepositoryDiscriminator(data: JsonObject?): RepositoryDiscriminatorRead {
    val raw = data?.get(REPOSITORY_DISCRIMINATOR_FIELD)
        ?: return RepositoryDiscriminatorRead.Absent
    if (raw is JsonNull) return RepositoryDiscriminatorRead.Absent
    if (raw !is JsonPrimitive || !raw.isString) {
        return RepositoryDiscriminatorRead.Malformed(RepositoryDiscriminatorProblem.NOT_A_STRING)
    }
    val value = raw.content
    if (value.isEmpty()) {
        return RepositoryDiscriminatorRead.Malformed(RepositoryDiscriminatorProblem.EMPTY)
    }
    if (!ROOT_COMMIT_SHA_RE.matches(value)) {
        return RepositoryDiscriminatorRead.Malformed(RepositoryDiscriminatorProblem.NOT_A_COMMIT_SHA)
    }
    return RepositoryDiscriminatorRead.Recorded(value)
}
