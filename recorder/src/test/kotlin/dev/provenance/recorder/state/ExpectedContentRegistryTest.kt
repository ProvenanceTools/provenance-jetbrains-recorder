package dev.provenance.recorder.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Pure JUnit 4 test — mirrors state/expected-content-registry.test.ts. */
class ExpectedContentRegistryTest {
    @Test
    fun `isWatched reflects the files_under_review set`() {
        val reg = ExpectedContentRegistry(listOf("src/Main.kt"))
        assertEquals(true, reg.isWatched("src/Main.kt"))
        assertEquals(false, reg.isWatched("src/Other.kt"))
    }

    @Test
    fun `getOrCreate returns the same instance on repeat calls`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        val first = reg.getOrCreate("a.txt", "v1")
        val second = reg.getOrCreate("a.txt", "ignored — already exists")
        assertSame(first, second)
        assertEquals("v1", second.content)
    }

    @Test
    fun `get returns null for an untracked path`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        assertNull(reg.get("a.txt"))
    }

    @Test
    fun `delete removes the entry so a later getOrCreate starts clean`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        reg.getOrCreate("a.txt", "v1")
        reg.delete("a.txt")
        val recreated = reg.getOrCreate("a.txt", "v2")
        assertEquals("v2", recreated.content)
    }
}
