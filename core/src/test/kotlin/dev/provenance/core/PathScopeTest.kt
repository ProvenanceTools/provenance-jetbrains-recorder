package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit port of log-core's `path-scope.test.ts`. These cover branches the
 * cross-port conformance vectors do not — `matchesAnyScopeEntry` on an empty
 * list, `isHardExcluded` NOT excluding a similarly-named ordinary file, and the
 * precedence chain end to end. The matching/validation RULES themselves are
 * pinned by [ConformanceTest.PathScopeVectors] against `path-scope-vectors.json`;
 * this file is for full branch coverage, not for re-deriving the contract.
 */
class PathScopeTest {
    @Test
    fun `exact form matches only the identical path`() {
        assertTrue(matchesScopeEntry("Makefile", "Makefile"))
        assertFalse(matchesScopeEntry("src/Makefile", "Makefile"))
        assertFalse(matchesScopeEntry("Makefile2", "Makefile"))
    }

    @Test
    fun `directory form matches everything beneath it, recursively`() {
        assertTrue(matchesScopeEntry("src/Main.java", "src/"))
        assertTrue(matchesScopeEntry("src/util/deep/A.java", "src/"))
        assertFalse(matchesScopeEntry("src", "src/"))
        assertFalse(matchesScopeEntry("srcx/A.java", "src/"))
    }

    @Test
    fun `suffix form matches at any depth`() {
        assertTrue(matchesScopeEntry("Main.java", "*.java"))
        assertTrue(matchesScopeEntry("src/util/Main.java", "*.java"))
        assertFalse(matchesScopeEntry("Main.javax", "*.java"))
    }

    @Test
    fun `matching is case-sensitive`() {
        assertFalse(matchesScopeEntry("Main.JAVA", "*.java"))
        assertFalse(matchesScopeEntry("SRC/A.java", "src/"))
    }

    @Test
    fun `isExactEntry is true only for the exact form`() {
        assertTrue(isExactEntry("Makefile"))
        assertTrue(isExactEntry("src/Main.java"))
        assertFalse(isExactEntry("src/"))
        assertFalse(isExactEntry("*.java"))
    }

    @Test
    fun `matchesAnyScopeEntry is false for an empty list and true on any hit`() {
        assertFalse(matchesAnyScopeEntry("a.java", emptyList()))
        assertTrue(matchesAnyScopeEntry("a.java", listOf("*.py", "*.java")))
    }

    @Test
    fun `validateScopeEntry accepts the three legal forms`() {
        assertNull(validateScopeEntry("Makefile"))
        assertNull(validateScopeEntry("src/util/Main.java"))
        assertNull(validateScopeEntry("src/"))
        assertNull(validateScopeEntry("*.java"))
    }

    @Test
    fun `validateScopeEntry rejects empty and surrounding whitespace`() {
        assertEquals(ScopeEntryProblemKind.EMPTY, validateScopeEntry("")?.kind)
        assertEquals(ScopeEntryProblemKind.WHITESPACE, validateScopeEntry(" src/")?.kind)
        assertEquals(ScopeEntryProblemKind.WHITESPACE, validateScopeEntry("Makefile ")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects backslashes so Windows spellings never reach the signed payload`() {
        assertEquals(ScopeEntryProblemKind.BACKSLASH, validateScopeEntry("src\\Main.java")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects absolute paths in both spellings`() {
        assertEquals(ScopeEntryProblemKind.ABSOLUTE, validateScopeEntry("/etc/passwd")?.kind)
        assertEquals(ScopeEntryProblemKind.ABSOLUTE, validateScopeEntry("C:/Users/a")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects dot and empty segments`() {
        assertEquals(ScopeEntryProblemKind.DOT_SEGMENT, validateScopeEntry("../secrets")?.kind)
        assertEquals(ScopeEntryProblemKind.DOT_SEGMENT, validateScopeEntry("src/../etc")?.kind)
        assertEquals(ScopeEntryProblemKind.DOT_SEGMENT, validateScopeEntry("./src/")?.kind)
        assertEquals(ScopeEntryProblemKind.EMPTY_SEGMENT, validateScopeEntry("src//a.java")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects every wildcard shape but a single leading star`() {
        assertEquals(ScopeEntryProblemKind.BAD_WILDCARD, validateScopeEntry("src/*.java")?.kind)
        assertEquals(ScopeEntryProblemKind.BAD_WILDCARD, validateScopeEntry("**/a.java")?.kind)
        assertEquals(ScopeEntryProblemKind.BAD_WILDCARD, validateScopeEntry("*")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects a suffix entry that also ends in slash — legal-looking but dead`() {
        // `matchesScopeEntry` tests the directory form FIRST, so `*.java/` can only
        // ever match a path literally starting `*.java/`. Accepting it signs a
        // manifest that watches nothing.
        assertEquals(ScopeEntryProblemKind.BAD_WILDCARD, validateScopeEntry("*.java/")?.kind)
        assertEquals(ScopeEntryProblemKind.BAD_WILDCARD, validateScopeEntry("*/")?.kind)
        assertFalse(matchesScopeEntry("src/Main.java", "*.java/"))
    }

    @Test
    fun `validateScopeEntry rejects glob metacharacters we do not implement`() {
        assertEquals(ScopeEntryProblemKind.FORBIDDEN_CHAR, validateScopeEntry("a?.java")?.kind)
        assertEquals(ScopeEntryProblemKind.FORBIDDEN_CHAR, validateScopeEntry("a[0-9].java")?.kind)
        assertEquals(ScopeEntryProblemKind.FORBIDDEN_CHAR, validateScopeEntry("{a,b}.java")?.kind)
    }

    @Test
    fun `validateScopeEntry rejects a bare slash`() {
        assertEquals(ScopeEntryProblemKind.ABSOLUTE, validateScopeEntry("/")?.kind)
    }

    @Test
    fun `isHardExcluded excludes the provenance and git directories and the manifest itself`() {
        assertTrue(isHardExcluded(".provenance/manifest.json"))
        assertTrue(isHardExcluded(".provenance/s1.slog"))
        assertTrue(isHardExcluded(".git/config"))
        assertTrue(isHardExcluded(".provenance-manifest"))
        assertTrue(isHardExcluded("provenance-manifest"))
    }

    @Test
    fun `isHardExcluded does not exclude ordinary files with similar names`() {
        assertFalse(isHardExcluded("provenance-notes.md"))
        assertFalse(isHardExcluded("src/.provenance-helper.ts"))
    }

    private val scope = ResolvedScope(
        track = listOf("src/", "Makefile"),
        ignore = listOf("*.class", "src/generated/"),
        attachments = listOf("logs/", "*.log"),
    )

    @Test
    fun `resolvePathRole - hard exclusion beats every course list`() {
        val greedy = ResolvedScope(track = listOf("*.json"), ignore = emptyList(), attachments = listOf("*.json"))
        assertEquals(PathRole.EXCLUDED, resolvePathRole(".provenance/manifest.json", greedy))
    }

    @Test
    fun `resolvePathRole - ignore beats attachments and track`() {
        assertEquals(PathRole.IGNORED, resolvePathRole("src/A.class", scope))
        assertEquals(PathRole.IGNORED, resolvePathRole("src/generated/G.java", scope))
    }

    @Test
    fun `resolvePathRole - attachments beat track`() {
        val overlap = ResolvedScope(track = listOf("src/"), ignore = emptyList(), attachments = listOf("src/build.log"))
        assertEquals(PathRole.ATTACHMENT, resolvePathRole("src/build.log", overlap))
    }

    @Test
    fun `resolvePathRole - tracks what only the track list matches`() {
        assertEquals(PathRole.REVIEWED, resolvePathRole("src/Main.java", scope))
        assertEquals(PathRole.REVIEWED, resolvePathRole("Makefile", scope))
    }

    @Test
    fun `resolvePathRole - leaves anything unmatched unscoped`() {
        assertEquals(PathRole.UNSCOPED, resolvePathRole("README.md", scope))
    }
}
