package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalTest {
    @Test
    fun `sorts object keys lexicographically`() {
        assertEquals(
            """{"a":2,"b":1}""",
            Canonical.canonicalize("""{"b":1,"a":2}"""),
        )
    }

    @Test
    fun `strips insignificant whitespace`() {
        assertEquals(
            """{"a":1}""",
            Canonical.canonicalize("{  \"a\" : 1  }"),
        )
    }

    @Test
    fun `sorts nested object keys`() {
        assertEquals(
            """{"outer":{"x":1,"y":2}}""",
            Canonical.canonicalize("""{"outer":{"y":2,"x":1}}"""),
        )
    }
}
