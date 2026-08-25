package dev.provenance.recorder.wiring

import dev.provenance.core.WitnessCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [probeWitnessCapture] tested as a pure(ish) function over real NIO paths — no IntelliJ SDK,
 * no VFS, no running IDE. Per CLAUDE.md: test the wiring's decision logic separately from the
 * platform seam it will eventually sit behind.
 */
class WitnessCapabilityProbeTest {
    @Test
    fun `a real, listable directory reports available`() {
        val dir = Files.createTempDirectory("provenance-witness-probe-test")
        try {
            assertEquals(WitnessCaptureCapability.AVAILABLE, probeWitnessCapture(dir))
        } finally {
            Files.delete(dir)
        }
    }

    @Test
    fun `a path that is a regular file, not a directory, reports unavailable`() {
        val file = Files.createTempFile("provenance-witness-probe-test", ".txt")
        try {
            var reported: Throwable? = null
            val capture = probeWitnessCapture(file) { reported = it }
            assertEquals(WitnessCaptureCapability.UNAVAILABLE, capture)
            assertTrue("onError must be told why", reported != null)
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `a path that does not exist reports unavailable`() {
        val missing = Files.createTempDirectory("provenance-witness-probe-test").resolve("does-not-exist")
        var reported: Throwable? = null
        val capture = probeWitnessCapture(missing) { reported = it }
        assertEquals(WitnessCaptureCapability.UNAVAILABLE, capture)
        assertTrue("onError must be told why", reported != null)
    }

    @Test
    fun `onError defaults to a no-op — probeWitnessCapture never throws on a bad path`() {
        val missing = Files.createTempDirectory("provenance-witness-probe-test").resolve("does-not-exist")
        assertEquals(WitnessCaptureCapability.UNAVAILABLE, probeWitnessCapture(missing))
    }
}
