package dev.provenance.recorder.state

import dev.provenance.core.ResolvedScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JUnit 4 test — mirrors state/expected-content-registry.test.ts. */
class ExpectedContentRegistryTest {

    private fun scope(track: List<String>, ignore: List<String> = emptyList(), attachments: List<String> = emptyList()) =
        ResolvedScope(track = track, ignore = ignore, attachments = attachments)

    private fun trackOnly(vararg paths: String) = scope(paths.toList())

    @Test
    fun `isWatched reflects the files_under_review set`() {
        val reg = ExpectedContentRegistry(trackOnly("src/Main.kt"))
        assertEquals(true, reg.isWatched("src/Main.kt"))
        assertEquals(false, reg.isWatched("src/Other.kt"))
    }

    @Test
    fun `getOrCreate returns the same instance on repeat calls`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt"))
        val first = reg.getOrCreate("a.txt", "v1")
        val second = reg.getOrCreate("a.txt", "ignored — already exists")
        assertSame(first, second)
        assertEquals("v1", second.content)
    }

    @Test
    fun `get returns null for an untracked path`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt"))
        assertNull(reg.get("a.txt"))
    }

    @Test
    fun `delete removes the entry so a later getOrCreate starts clean`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt"))
        reg.getOrCreate("a.txt", "v1")
        reg.delete("a.txt")
        val recreated = reg.getOrCreate("a.txt", "v2")
        assertEquals("v2", recreated.content)
    }

    // -----------------------------------------------------------------------
    // Live membership (design spec §5) — a rule entry cannot be enumerated at
    // construction, so isWatched must evaluate resolvePathRole per call, never a
    // snapshotted set.
    // -----------------------------------------------------------------------

    @Test
    fun `a rule entry admits a path unknown at construction`() {
        val reg = ExpectedContentRegistry(trackOnly("src/"))
        assertTrue(
            "a file the student creates mid-session under a tracked folder must be watched " +
                "from its first keystroke, not only files present when the registry was built",
            reg.isWatched("src/NewFile.kt"),
        )
    }

    @Test
    fun `hard exclusion beats the course track list`() {
        val reg = ExpectedContentRegistry(trackOnly("*.json"))
        assertFalse(".provenance/x.json must never be reviewed, whatever the course lists", reg.isWatched(".provenance/x.json"))
    }

    @Test
    fun `an ignored path is not watched even when track also matches it`() {
        val reg = ExpectedContentRegistry(scope(track = listOf("*.py"), ignore = listOf("secret.py")))
        assertFalse(reg.isWatched("secret.py"))
        assertTrue(reg.isWatched("other.py"))
    }

    @Test
    fun `an attachment path is not watched`() {
        val reg = ExpectedContentRegistry(scope(track = listOf("*.pdf"), attachments = listOf("*.pdf")))
        assertFalse("role is attachment, not reviewed, so it must not be watched", reg.isWatched("handout.pdf"))
    }

    // -----------------------------------------------------------------------
    // The cap
    // -----------------------------------------------------------------------

    @Test
    fun `the cap refuses admission past the max and flips capHit`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt", "b.txt", "c.txt"), maxFiles = 2)
        assertTrue(reg.isWatched("a.txt"))
        reg.getOrCreate("a.txt", "1")
        assertTrue(reg.isWatched("b.txt"))
        reg.getOrCreate("b.txt", "2")

        assertFalse("the third distinct path exceeds the cap", reg.isWatched("c.txt"))
        assertTrue(reg.capHit())
    }

    @Test
    fun `a path that was never in scope does not flip capHit`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt"), maxFiles = 1)
        assertTrue(reg.isWatched("a.txt"))
        reg.getOrCreate("a.txt", "1")

        assertFalse(reg.isWatched("out-of-scope.txt"))
        assertFalse(
            "the cap did not cost us this file — it was never watchable in the first place",
            reg.capHit(),
        )
    }

    @Test
    fun `an already-tracked path stays watched even after the cap has bitten`() {
        val reg = ExpectedContentRegistry(trackOnly("a.txt", "b.txt"), maxFiles = 1)
        assertTrue(reg.isWatched("a.txt"))
        reg.getOrCreate("a.txt", "1")

        assertFalse(reg.isWatched("b.txt")) // cap bites here
        assertTrue(reg.capHit())

        assertTrue(
            "a path admitted once must stay admitted, or its ExpectedContent baseline goes stale",
            reg.isWatched("a.txt"),
        )
    }
}
