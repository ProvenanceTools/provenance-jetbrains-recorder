package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BufferPolicyTest {
    @Test
    fun `empty buffer never flushes even past the interval`() {
        assertFalse(shouldFlush(BufferPolicyInput(bufferedBytes = 0, lastFlushAtMs = 0, nowMs = 100_000)))
    }

    @Test
    fun `flushes when buffered bytes reach maxBytes`() {
        val cfg = BufferPolicyConfig(maxBytes = 256 * 1024, maxIntervalMs = 1000)
        assertTrue(shouldFlush(BufferPolicyInput(bufferedBytes = 256 * 1024, lastFlushAtMs = 0, nowMs = 0), cfg))
        assertTrue(shouldFlush(BufferPolicyInput(bufferedBytes = 256 * 1024 + 1, lastFlushAtMs = 0, nowMs = 0), cfg))
    }

    @Test
    fun `flushes when interval elapsed`() {
        val cfg = BufferPolicyConfig(maxBytes = 256 * 1024, maxIntervalMs = 1000)
        assertTrue(shouldFlush(BufferPolicyInput(bufferedBytes = 10, lastFlushAtMs = 0, nowMs = 1000), cfg))
        assertTrue(shouldFlush(BufferPolicyInput(bufferedBytes = 10, lastFlushAtMs = 0, nowMs = 1001), cfg))
    }

    @Test
    fun `does not flush below both thresholds`() {
        val cfg = BufferPolicyConfig(maxBytes = 256 * 1024, maxIntervalMs = 1000)
        assertFalse(shouldFlush(BufferPolicyInput(bufferedBytes = 10, lastFlushAtMs = 0, nowMs = 999), cfg))
    }

    @Test
    fun `default policy is 256KiB and 1000ms`() {
        assertTrue(DEFAULT_BUFFER_POLICY.maxBytes == 256 * 1024)
        assertTrue(DEFAULT_BUFFER_POLICY.maxIntervalMs == 1000L)
    }
}
