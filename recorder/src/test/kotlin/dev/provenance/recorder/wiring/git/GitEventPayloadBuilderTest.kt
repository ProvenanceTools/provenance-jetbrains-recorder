package dev.provenance.recorder.wiring.git

import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commit-graph payload rules (program spec S5), and the protocol constraint that no git
 * author identity is ever captured.
 */
class GitEventPayloadBuilderTest {

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun reader(parents: List<String>?) = GitCommitGraphReader { sha ->
        GitCommitView(sha = sha, parents = parents)
    }

    // -----------------------------------------------------------------------
    // NO AUTHOR IDENTITY — the protocol constraint
    // -----------------------------------------------------------------------

    /**
     * A commit object that DOES carry author identity must not leak any of it.
     *
     * The approved CPHS protocol treats a new category of identifier as requiring a filed
     * modification BEFORE implementation, so this is a regulatory constraint, not a style
     * preference. It is enforced structurally — [GitCommitView] declares only `sha` and
     * `parents`, so author fields are unreachable rather than merely unused — and this test
     * is the behavioural proof: a reader whose underlying commit is loaded with a real name,
     * a real email, a date and a message emits none of them.
     */
    @Test
    fun `no git author identity reaches the payload`() {
        // A commit object shaped like git4idea's, carrying everything we must never record.
        data class FakeGitCommit(
            val hash: String,
            val parents: List<String>,
            val authorName: String,
            val authorEmail: String,
            val authorDate: String,
            val message: String,
        )

        val real = FakeGitCommit(
            hash = b,
            parents = listOf(a),
            authorName = "Ada Lovelace",
            authorEmail = "ada@berkeley.edu",
            authorDate = "2026-09-08T12:00:00Z",
            message = "proj2: fix the off-by-one in Deque",
        )

        // The projection into GitCommitView is the only path in — exactly as the production
        // reader does it. There is no field on GitCommitView that could carry the rest.
        val payload = buildGitEventPayload(
            operation = "commit",
            sha = real.hash,
            branch = "main",
            reader = GitCommitGraphReader { GitCommitView(sha = real.hash, parents = real.parents) },
            rootCommitSha = null,
        )

        val json = payload.toJsonObject()
        // The key set is closed: exactly the structural fields, nothing else.
        assertEquals(
            setOf("operation", "commit_sha", "sha", "parents", "branch"),
            json.keys,
        )
        for (forbidden in listOf("author", "author_name", "author_email", "authorName", "authorEmail", "date", "author_date", "message", "commit_message")) {
            assertFalse("payload must not carry $forbidden", forbidden in json)
        }
        // And none of the identifying VALUES appear anywhere in the serialized payload.
        val serialized = json.toString()
        for (secret in listOf(real.authorName, real.authorEmail, real.authorDate, real.message, "Ada", "ada@", "berkeley.edu")) {
            assertFalse("payload must not contain '$secret'", serialized.contains(secret))
        }
    }

    // -----------------------------------------------------------------------
    // parents: order, and empty-vs-absent
    // -----------------------------------------------------------------------

    /**
     * The first parent is the branch that was merged INTO, so the order carries meaning.
     * JCS canonicalizes object keys but leaves array elements alone, which means a helpful
     * sort here would change the signed bytes and the chain hash as well as the semantics.
     */
    @Test
    fun `parents order is preserved exactly and never sorted`() {
        // Deliberately in an order a sort would change.
        val merge = buildGitEventPayload("commit", c, "main", reader(listOf(b, a)), rootCommitSha = null)
        assertEquals(listOf(b, a), merge.parents)
        assertEquals(
            listOf(b, a),
            merge.toJsonObject()["parents"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * `[]` is a positive claim — "this commit genuinely has no parents", i.e. a root commit.
     * Absent means "the recorder could not read them". A read failure is not entitled to
     * make the former claim, so the two must never collapse into one.
     */
    @Test
    fun `an empty parent list is distinct from an absent one`() {
        val rootCommit = buildGitEventPayload("commit", a, "main", reader(emptyList()), rootCommitSha = null)
        assertEquals(emptyList<String>(), rootCommit.parents)
        assertTrue("[] must survive to the wire", "parents" in rootCommit.toJsonObject())
        assertEquals(0, rootCommit.toJsonObject()["parents"]!!.jsonArray.size)

        val unknown = buildGitEventPayload("commit", a, "main", reader(null), rootCommitSha = null)
        assertNull(unknown.parents)
        assertFalse("unknown parents must be OMITTED", "parents" in unknown.toJsonObject())

        // A reader that fails outright is "unknown", never "root commit".
        val throwing = GitCommitGraphReader { throw IllegalStateException("shallow clone") }
        val failed = buildGitEventPayload("commit", a, "main", throwing, rootCommitSha = null)
        assertNull(failed.parents)
        assertFalse("parents" in failed.toJsonObject())

        // So is having no reader at all (Git4Idea absent / older API).
        val noReader = buildGitEventPayload("commit", a, "main", null, rootCommitSha = null)
        assertNull(noReader.parents)
        assertFalse("parents" in noReader.toJsonObject())
    }

    // -----------------------------------------------------------------------
    // sha / commit_sha / branch
    // -----------------------------------------------------------------------

    /** `commit_sha` duplicates `sha` on purpose: 1.x readers only know the former. */
    @Test
    fun `commit_sha is still emitted alongside sha for 1_x readers`() {
        val json = buildGitEventPayload("commit", b, "main", reader(listOf(a)), rootCommitSha = null).toJsonObject()
        assertEquals(b, json["commit_sha"]!!.jsonPrimitive.content)
        assertEquals(b, json["sha"]!!.jsonPrimitive.content)
    }

    /** Detached HEAD omits `branch` entirely rather than inventing a name for it. */
    @Test
    fun `branch is omitted on detached HEAD`() {
        val json = buildGitEventPayload("checkout", a, null, reader(emptyList()), rootCommitSha = null).toJsonObject()
        assertFalse("branch" in json)
        assertEquals(setOf("operation", "commit_sha", "sha", "parents"), json.keys)
    }

    /** A repository with no HEAD at all (fresh `git init`) still emits a usable event. */
    @Test
    fun `a repo with no HEAD emits operation only and never consults the reader`() {
        var consulted = false
        val spy = GitCommitGraphReader { consulted = true; null }
        val json = buildGitEventPayload("state_change", null, null, spy, rootCommitSha = null).toJsonObject()
        assertEquals(setOf("operation"), json.keys)
        assertFalse("no sha means nothing to resolve", consulted)
    }
}
