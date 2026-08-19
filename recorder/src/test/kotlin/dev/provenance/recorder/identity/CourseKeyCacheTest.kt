package dev.provenance.recorder.identity

import dev.provenance.core.StudentCourseKeypair
import dev.provenance.core.deriveCourseKeypair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CourseKeyCache]: the recorder-layer derived-key cache (program spec §5a).
 *
 * The cache is a PERFORMANCE detail and must never be a correctness one, so the load-bearing
 * assertions here are that a cached key is byte-identical to a directly-derived one, that a
 * different master secret can never yield a stale key, and that disposal actually drops the
 * retained private keys.
 */
class CourseKeyCacheTest {

    private val masterA = ByteArray(32) { 0x2a }
    private val masterB = ByteArray(32) { 0x77 }
    private val course = "berkeley-cs61b"

    /** Counts derivations so a cache HIT is observable rather than merely plausible. */
    private class CountingDerive : (ByteArray, String) -> StudentCourseKeypair {
        val calls = AtomicInteger(0)
        override fun invoke(master: ByteArray, courseId: String): StudentCourseKeypair {
            calls.incrementAndGet()
            return deriveCourseKeypair(master, courseId)
        }
    }

    // -----------------------------------------------------------------------
    // The cache changes timing, never bytes
    // -----------------------------------------------------------------------

    /**
     * The whole point: a cached key must equal the key `core` derives directly. If these ever
     * diverge, a student's countersignature stops verifying against the pubkey their token
     * names — and a signature that does not verify looks exactly like tampering.
     */
    @Test
    fun `a cached key is byte-identical to a directly derived one`() {
        val cache = CourseKeyCache()
        val direct = deriveCourseKeypair(masterA, course)

        val first = cache.get(masterA, course)!!
        val second = cache.get(masterA, course)!!

        assertEquals(direct.publicKeyHex, first.publicKeyHex)
        assertTrue(direct.privateKey.contentEquals(first.privateKey))
        // ...and the hit is the same value, not merely an equal one.
        assertEquals(direct.publicKeyHex, second.publicKeyHex)
        assertTrue(direct.privateKey.contentEquals(second.privateKey))
    }

    @Test
    fun `a repeat request for the same course derives exactly once`() {
        val derive = CountingDerive()
        val cache = CourseKeyCache(derive)

        val a = cache.get(masterA, course)!!
        val b = cache.get(masterA, course)!!
        val c = cache.get(masterA, course)!!

        assertEquals("a cache hit must not re-derive", 1, derive.calls.get())
        assertEquals(a.publicKeyHex, b.publicKeyHex)
        assertEquals(a.publicKeyHex, c.publicKeyHex)
        assertEquals(1, cache.size)
    }

    @Test
    fun `each course gets its own key`() {
        val derive = CountingDerive()
        val cache = CourseKeyCache(derive)

        val b = cache.get(masterA, "berkeley-cs61b")!!
        val c = cache.get(masterA, "berkeley-cs61c")!!

        assertEquals(2, derive.calls.get())
        assertEquals(2, cache.size)
        // Unlinkability: two courses must not see the same public key.
        assertFalse(b.publicKeyHex == c.publicKeyHex)
        assertEquals(deriveCourseKeypair(masterA, "berkeley-cs61c").publicKeyHex, c.publicKeyHex)
    }

    /**
     * The trap the fingerprint exists for. A student who imports a DIFFERENT master secret
     * mid-session — moving machines, or fixing a bad paste — must never keep receiving keys
     * derived from the old one. Keyed on `course_id` alone, this returns the stale key and the
     * resulting countersignature cannot verify against the token they hold.
     */
    @Test
    fun `a different master secret never returns the first secret's key`() {
        val derive = CountingDerive()
        val cache = CourseKeyCache(derive)

        val fromA = cache.get(masterA, course)!!
        val fromB = cache.get(masterB, course)!!

        assertEquals("a new master secret must force a fresh derivation", 2, derive.calls.get())
        assertFalse(fromA.publicKeyHex == fromB.publicKeyHex)
        assertEquals(deriveCourseKeypair(masterB, course).publicKeyHex, fromB.publicKeyHex)
        // Both remain cached under distinct fingerprints, and A is still A.
        assertEquals(2, cache.size)
        assertEquals(fromA.publicKeyHex, cache.get(masterA, course)!!.publicKeyHex)
        assertEquals(2, derive.calls.get())
    }

    /** The cache key is a HASH of the secret, so the secret itself is never a lookup key. */
    @Test
    fun `an equal-valued but distinct secret array hits the same entry`() {
        val derive = CountingDerive()
        val cache = CourseKeyCache(derive)

        cache.get(ByteArray(32) { 0x2a }, course)
        cache.get(ByteArray(32) { 0x2a }, course)

        // Keyed by content fingerprint, not array identity — a fresh copy of the same secret
        // must hit, or every session start would re-derive.
        assertEquals(1, derive.calls.get())
        assertEquals(1, cache.size)
    }

    // -----------------------------------------------------------------------
    // Teardown: no private key outlives disposal
    // -----------------------------------------------------------------------

    /**
     * Disposal is the entire reason this cache lives in the recorder layer rather than as a
     * memo table inside pure `core/`. It must actually drop the retained private keys.
     */
    @Test
    fun `dispose drops every retained key`() {
        val cache = CourseKeyCache()
        cache.get(masterA, "berkeley-cs61b")
        cache.get(masterA, "berkeley-cs61c")
        assertEquals(2, cache.size)

        cache.dispose()

        assertEquals("dispose must drop every retained private key", 0, cache.size)
    }

    /**
     * A disposed cache still ANSWERS — callers must keep working — it simply stops retaining.
     * Returning null after disposal would turn plugin teardown into a reason a session
     * silently records without an identity.
     */
    @Test
    fun `a disposed cache still derives correctly but retains nothing`() {
        val derive = CountingDerive()
        val cache = CourseKeyCache(derive)
        cache.dispose()

        val k = cache.get(masterA, course)
        assertNotNull("a disposed cache must still answer", k)
        assertEquals(deriveCourseKeypair(masterA, course).publicKeyHex, k!!.publicKeyHex)
        assertEquals("nothing may be retained after disposal", 0, cache.size)

        // Still not retaining on a second call: it re-derives rather than caching again.
        cache.get(masterA, course)
        assertEquals(2, derive.calls.get())
        assertEquals(0, cache.size)
    }

    @Test
    fun `dispose is idempotent`() {
        val cache = CourseKeyCache()
        cache.get(masterA, course)
        cache.dispose()
        cache.dispose()
        assertEquals(0, cache.size)
    }

    // -----------------------------------------------------------------------
    // Never throws: this sits on the session-start path
    // -----------------------------------------------------------------------

    /**
     * Rule 1 reaches down to here. A caching layer must never be the reason a student's work
     * goes unrecorded, so every failure is a null and the builder skips the identity block.
     */
    @Test
    fun `a throwing derivation returns null instead of propagating`() {
        val cache = CourseKeyCache { _, _ -> throw IllegalStateException("no ed25519 provider") }
        assertNull(cache.get(masterA, course))
        assertEquals(0, cache.size)
    }

    @Test
    fun `an unusable input returns null`() {
        val cache = CourseKeyCache()
        // Empty course id: core's deriveCourseKeySeed would throw on this.
        assertNull(cache.get(masterA, ""))
        // A wrong-length master secret is likewise a null, not a throw.
        assertNull(cache.get(ByteArray(7), course))
        assertEquals(0, cache.size)
    }

    // -----------------------------------------------------------------------
    // Concurrency: session start runs off the EDT, and projects activate in parallel
    // -----------------------------------------------------------------------

    /**
     * Two projects can activate concurrently, and session start runs off the EDT. Whatever the
     * interleaving, every caller must get the CORRECT key for its own course — a torn read of
     * the entry map that handed one course another course's key would produce a
     * countersignature that cannot verify.
     */
    @Test
    fun `concurrent callers each receive the correct key for their own course`() {
        val cache = CourseKeyCache()
        val courses = listOf("berkeley-cs61a", "berkeley-cs61b", "berkeley-cs61c", "berkeley-café")
        val expected = courses.associateWith { deriveCourseKeypair(masterA, it).publicKeyHex }

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val mismatches = AtomicInteger(0)
        try {
            val futures = (0 until threads).map { i ->
                pool.submit {
                    val courseId = courses[i % courses.size]
                    start.await()
                    repeat(20) {
                        val got = cache.get(masterA, courseId)
                        if (got == null || got.publicKeyHex != expected[courseId]) {
                            mismatches.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("every concurrent caller must get its own course's key", 0, mismatches.get())
        assertEquals(courses.size, cache.size)
    }
}
