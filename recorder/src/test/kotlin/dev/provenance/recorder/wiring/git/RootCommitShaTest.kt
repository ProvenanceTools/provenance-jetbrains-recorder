package dev.provenance.recorder.wiring.git

import dev.provenance.core.REPOSITORY_DISCRIMINATOR_FIELD
import dev.provenance.core.RepositoryDiscriminatorRead
import dev.provenance.core.readRepositoryDiscriminator
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE REPOSITORY DISCRIMINATOR, writer half (decision D12).
 *
 * Pure tests against the [GitPlumbing] seam — no git binary, no IntelliJ platform. The
 * production seam runs the same two commands through the IntelliJ VCS API; that half is
 * covered end-to-end against a REAL repository by `GitExternalChangeGateTest`.
 */
class RootCommitShaTest {

    private val repo: Path = Paths.get("/tmp/repo")

    private val ROOT_A = "1111111111111111111111111111111111111111"
    private val ROOT_B = "2222222222222222222222222222222222222222"
    private val ROOT_SHA256 =
        "3333333333333333333333333333333333333333333333333333333333333333"

    /** Records every invocation, so a test can assert on the exact argument list. */
    private class RecordingGit(
        private val answers: Map<String, List<String>>,
        private val failOn: Set<String> = emptySet(),
    ) : GitPlumbing {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override fun run(repoRoot: Path, command: String, args: List<String>): List<String> {
            calls.add(command to args)
            if (command in failOn) throw IllegalStateException("git $command failed")
            return answers[command] ?: emptyList()
        }
    }

    private fun git(
        shallow: String = "false",
        roots: List<String> = listOf(ROOT_A),
        failOn: Set<String> = emptySet(),
    ) = RecordingGit(
        mapOf("rev-parse" to listOf(shallow), "rev-list" to roots),
        failOn,
    )

    // -----------------------------------------------------------------------
    // The value
    // -----------------------------------------------------------------------

    /**
     * The exact commands, with the exact arguments, in the exact order. `--first-parent` is
     * the whole point: that lineage stays on the mainline when an imported history is merged
     * in, which is what keeps two partners agreeing. A port that dropped it would derive a
     * DIFFERENT value from its partner on the same repository and correlate nothing.
     */
    @Test
    fun `derives the root of HEAD's first-parent lineage`() {
        val g = git(roots = listOf(ROOT_A))
        assertEquals(ROOT_A, deriveRootCommitSha(repo, g))
        assertEquals(
            listOf(
                "rev-parse" to listOf("--is-shallow-repository"),
                "rev-list" to listOf("--max-parents=0", "--first-parent", "HEAD"),
            ),
            g.calls,
        )
    }

    /**
     * Several roots — an orphan branch, a squashed import merged in — is ORDINARY and never a
     * finding. Lexicographically smallest, so two partners with the same history agree
     * regardless of the order git happens to print them in.
     */
    @Test
    fun `several roots resolve to the lexicographically smallest, whatever the print order`() {
        assertEquals(ROOT_A, deriveRootCommitSha(repo, git(roots = listOf(ROOT_A, ROOT_B))))
        assertEquals(ROOT_A, deriveRootCommitSha(repo, git(roots = listOf(ROOT_B, ROOT_A))))
    }

    /** sha-256 repositories print 64 hex, and that is a legal object name too. */
    @Test
    fun `a sha-256 repository's 64-hex root is accepted`() {
        assertEquals(ROOT_SHA256, deriveRootCommitSha(repo, git(roots = listOf(ROOT_SHA256))))
    }

    /** Trailing newlines and stray whitespace are git's, not ours. */
    @Test
    fun `whitespace and blank lines around the output are tolerated`() {
        val g = RecordingGit(
            mapOf("rev-parse" to listOf(" false "), "rev-list" to listOf("", "  $ROOT_A  ", "")),
        )
        assertEquals(ROOT_A, deriveRootCommitSha(repo, g))
    }

    // -----------------------------------------------------------------------
    // Absence — legal, permanent, blameless
    // -----------------------------------------------------------------------

    /**
     * A shallow clone's boundary commit has no parents and is NOT a root, so emitting it would
     * publish a value a full clone of the same repository disagrees with — a silent failure to
     * correlate dressed as a successful one. The `rev-list` is not even run.
     */
    @Test
    fun `a shallow repository omits the field and never reaches rev-list`() {
        val g = git(shallow = "true", roots = listOf(ROOT_A))
        assertNull(deriveRootCommitSha(repo, g))
        assertEquals(listOf("rev-parse"), g.calls.map { it.first })
    }

    /**
     * Anything but a DEFINITE `false` omits. `--is-shallow-repository` needs git >= 2.15; an
     * older git errors out and lands in "omit on any failure", which is the mechanism rather
     * than a special case — and an unrecognised answer must never be read as "not shallow".
     */
    @Test
    fun `only a definite false is read as not-shallow`() {
        for (answer in listOf("true", "", "yes", "FALSE", "false ignore me")) {
            assertNull(answer, deriveRootCommitSha(repo, git(shallow = answer)))
        }
        assertNull("no output at all", deriveRootCommitSha(repo, RecordingGit(emptyMap())))
    }

    /** git missing, not a repository, a timeout, permission denied — all one answer. */
    @Test
    fun `any failure omits rather than guessing`() {
        assertNull(deriveRootCommitSha(repo, git(failOn = setOf("rev-parse"))))
        assertNull(deriveRootCommitSha(repo, git(failOn = setOf("rev-list"))))
        assertNull(
            deriveRootCommitSha(repo) { _, _, _ -> throw NoClassDefFoundError("git4idea") },
        )
    }

    /** An empty repository: `HEAD` does not resolve, so `rev-list` yields nothing. */
    @Test
    fun `an empty repository omits`() {
        assertNull(deriveRootCommitSha(repo, git(roots = emptyList())))
    }

    /**
     * A `VirtualMachineError` PROPAGATES. Everything else is degraded to an omission, but
     * swallowing an `OutOfMemoryError` to keep witnessing would be pretending the process is
     * healthy when nothing about it is.
     */
    @Test
    fun `a VirtualMachineError is not swallowed`() {
        var thrown = false
        try {
            deriveRootCommitSha(repo) { _, _, _ -> throw StackOverflowError("boom") }
        } catch (_: VirtualMachineError) {
            thrown = true
        }
        assertTrue("a VirtualMachineError must propagate", thrown)
    }

    // -----------------------------------------------------------------------
    // The shape check, through the reader
    // -----------------------------------------------------------------------

    /**
     * A repository PATH and a remote URL are the two identifiers S14(b) forbids in this field
     * — a path is arguably an identifier and a remote URL embeds the org and frequently the
     * student's own username. Neither can be written down, because the candidate goes through
     * `core`'s READER before it is accepted.
     *
     * An abbreviated or uppercased sha is rejected on the same path. Rejecting costs only
     * correlation; the observation still lands, unlabelled.
     */
    @Test
    fun `a path, a URL, an abbreviation and an uppercase sha are all refused`() {
        val refused = listOf(
            "/Users/student/cs61b/proj2",
            "git@github.com:berkeley-cs61b/student-proj2.git",
            "https://github.com/berkeley-cs61b/student-proj2",
            "9999999",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
            "",
        )
        for (value in refused) {
            assertNull(value, deriveRootCommitSha(repo, git(roots = listOf(value))))
        }
    }

    /**
     * A usable root among unusable ones still wins — and it is the SMALLEST USABLE one, not
     * the smallest line. `/Users/...` sorts before any hex digit, so a port that filtered
     * after sorting would return a filesystem path.
     */
    @Test
    fun `an unusable line never displaces a usable root`() {
        val g = git(roots = listOf("/Users/student/proj", ROOT_B, "9999999", ROOT_A))
        assertEquals(ROOT_A, deriveRootCommitSha(repo, g))
    }

    /** Whatever comes back is something `core`'s reader accepts. Nothing else can be emitted. */
    @Test
    fun `every derived value narrows as recorded by the shared reader`() {
        for (roots in listOf(listOf(ROOT_A), listOf(ROOT_SHA256), listOf(ROOT_B, ROOT_A))) {
            val derived = deriveRootCommitSha(repo, git(roots = roots))!!
            val payload = dev.provenance.core.GitEventPayload(
                operation = "state_change",
                commitSha = ROOT_A,
                sha = ROOT_A,
                rootCommitSha = derived,
            )
            assertTrue(
                readRepositoryDiscriminator(payload.toJsonObject())
                    is RepositoryDiscriminatorRead.Recorded,
            )
        }
    }

    // -----------------------------------------------------------------------
    // OMIT, never null
    // -----------------------------------------------------------------------

    /**
     * Rule 6, structurally: an omitted discriminator produces an ABSENT KEY, never a JSON
     * `null`. The two canonicalize differently and therefore chain to different hashes, so a
     * `null`-emitting writer produces a log whose entries hash differently from every other
     * recorder's for the identical observation.
     */
    @Test
    fun `an omitted discriminator leaves the key out entirely`() {
        val json = buildGitEventPayload(
            operation = "state_change",
            sha = ROOT_A,
            branch = "main",
            reader = null,
            rootCommitSha = null,
        ).toJsonObject()
        assertFalse(REPOSITORY_DISCRIMINATOR_FIELD in json)
        assertEquals(RepositoryDiscriminatorRead.Absent, readRepositoryDiscriminator(json))
    }

    /**
     * Rule 10: the label rides on every event that carries a `sha`, not only on commits — an
     * unlabelled observation does not correlate even when its neighbours in the same session
     * do. And an event with NO sha carries no label, because it places no commit.
     */
    @Test
    fun `the label rides on every event with a sha, and on none without one`() {
        val labelled = buildGitEventPayload(
            operation = "state_change",
            sha = ROOT_B,
            branch = "main",
            reader = null,
            rootCommitSha = ROOT_A,
        ).toJsonObject()
        assertEquals(ROOT_A, labelled[REPOSITORY_DISCRIMINATOR_FIELD]!!.jsonPrimitive.content)

        val headless = buildGitEventPayload(
            operation = "state_change",
            sha = null,
            branch = null,
            reader = null,
            rootCommitSha = ROOT_A,
        ).toJsonObject()
        assertEquals(setOf("operation"), headless.keys)
    }

    // -----------------------------------------------------------------------
    // Once per repository, and one value per repository OBSERVED
    // -----------------------------------------------------------------------

    /** Rule 1: derived ONCE per repository, never per event. */
    @Test
    fun `the discriminator is derived once per repository and memoized`() {
        val seen = Collections.synchronizedList(mutableListOf<Path>())
        val memo = RepositoryDiscriminators { root -> seen.add(root); ROOT_A }

        repeat(50) { assertEquals(ROOT_A, memo.of(repo)) }
        assertEquals(1, memo.derivationCount())
        assertEquals(listOf(repo), seen)
    }

    /**
     * An OMISSION is memoized too. A shallow clone's answer is permanent, so re-deriving it
     * per event would spend a git invocation per event to learn the same "no" — exactly the
     * per-event cost rule 1 forbids, and invisible because the payload looks identical either
     * way.
     */
    @Test
    fun `a null answer is memoized rather than re-derived every event`() {
        val memo = RepositoryDiscriminators { null }
        repeat(50) { assertNull(memo.of(repo)) }
        assertEquals(1, memo.derivationCount())
    }

    /**
     * RULE 9. A submodule is its own repository with its own root, and labelling its events
     * with the OUTER repository's root re-creates the exact sha-space merge this field exists
     * to prevent — the failure would be silent and would look like successful correlation.
     */
    @Test
    fun `a submodule gets its own value, keyed and derived on its own root`() {
        val outer = Paths.get("/tmp/outer")
        val submodule = Paths.get("/tmp/outer/vendor/lib")
        val byRoot = mapOf(outer to ROOT_A, submodule to ROOT_B)
        val derivedFor = mutableListOf<Path>()
        val memo = RepositoryDiscriminators { root -> derivedFor.add(root); byRoot[root] }

        assertEquals(ROOT_A, memo.of(outer))
        assertEquals(ROOT_B, memo.of(submodule))
        // Derived with each repository's OWN root as the working directory.
        assertEquals(listOf(outer, submodule), derivedFor)
        // And still memoized per root.
        assertEquals(ROOT_A, memo.of(outer))
        assertEquals(ROOT_B, memo.of(submodule))
        assertEquals(2, memo.derivationCount())
    }

    /** No repository root means no label — and no git invocation for an event nobody records. */
    @Test
    fun `a null repository root yields no label and no derivation`() {
        val memo = RepositoryDiscriminators { ROOT_A }
        assertNull(memo.of(null))
        assertEquals(0, memo.derivationCount())
    }

    /**
     * Concurrent first sightings of one repository still derive once. The git wiring emits
     * from a single-threaded executor today, so this is a property of the memo rather than a
     * live race — which is the point: it stays true if that executor is ever widened.
     */
    @Test
    fun `concurrent first sightings still derive exactly once`() {
        val memo = RepositoryDiscriminators { Thread.sleep(5); ROOT_A }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val results = Collections.synchronizedList(mutableListOf<String?>())
        repeat(8) {
            pool.execute {
                start.await()
                results.add(memo.of(repo))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(List(8) { ROOT_A }, results)
        assertEquals(1, memo.derivationCount())
    }
}
