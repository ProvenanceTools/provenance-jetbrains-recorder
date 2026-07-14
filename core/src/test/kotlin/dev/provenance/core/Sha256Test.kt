package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Sha256Test {
    @Test
    fun `hex of hello world matches NIST vector`() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            Sha256.hex("hello world"),
        )
    }

    @Test
    fun `hex of empty string matches NIST vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(""),
        )
    }

    @Test
    fun `hex is 64 lowercase hex chars`() {
        val h = Sha256.hex("anything")
        assertEquals(64, h.length)
        assert(h.matches(Regex("^[0-9a-f]{64}$")))
    }
}
