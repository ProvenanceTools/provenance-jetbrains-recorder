package dev.provenance.recorder.activation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape checks on the two embedded trust anchors. The Gradle `embedTrustAnchors`
 * task rewrites both constants in place by regex, so a drifted shape here is a
 * release-time failure, not a compile-time one.
 */
class TrustAnchorKeysTest {
    @Test
    fun `root public key is 64 lowercase hex chars`() {
        assertEquals(64, ROOT_PUBLIC_KEY_HEX.length)
        assertTrue(ROOT_PUBLIC_KEY_HEX.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `legacy course public key is 64 lowercase hex chars`() {
        assertEquals(64, LEGACY_COURSE_PUBLIC_KEY_HEX.length)
        assertTrue(LEGACY_COURSE_PUBLIC_KEY_HEX.matches(Regex("^[0-9a-f]{64}$")))
    }

    /**
     * Two distinct anchors, deliberately. If these ever collapse to one value the
     * routing in ManifestActivation.kt stops meaning anything and a 1.x manifest
     * would be indistinguishable from a root-signed one.
     */
    @Test
    fun `the two anchors are distinct keys`() {
        assertNotEquals(ROOT_PUBLIC_KEY_HEX, LEGACY_COURSE_PUBLIC_KEY_HEX)
    }
}
