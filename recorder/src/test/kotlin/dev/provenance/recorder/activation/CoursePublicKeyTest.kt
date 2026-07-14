package dev.provenance.recorder.activation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePublicKeyTest {
    @Test
    fun `course public key is 64 lowercase hex chars`() {
        assertEquals(64, COURSE_PUBLIC_KEY_HEX.length)
        assertTrue(COURSE_PUBLIC_KEY_HEX.matches(Regex("^[0-9a-f]{64}$")))
    }
}
