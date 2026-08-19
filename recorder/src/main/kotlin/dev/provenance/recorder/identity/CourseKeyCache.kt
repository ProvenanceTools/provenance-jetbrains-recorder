package dev.provenance.recorder.identity

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import dev.provenance.core.Sha256
import dev.provenance.core.StudentCourseKeypair
import dev.provenance.core.deriveCourseKeypair

/**
 * Per-course student key cache (program spec §5a, §S2).
 * The JetBrains twin of the Neovim recorder's `identity/key_cache.lua`.
 *
 * ## Why the cache lives HERE and not in `core/`
 *
 * `core/StudentKeys.kt` deliberately does not memoize. `core/` is pure, and a top-level
 * `object` holding a map there would retain a student's derived PRIVATE KEY for the whole
 * process lifetime with **no owner and no teardown path** — strictly worse than the
 * microseconds it saves.
 *
 * This is the recorder-layer answer: an application-level light service, so it has exactly
 * one owner (the platform's service container) and exactly one disposal path ([dispose],
 * which the platform calls on plugin unload / app shutdown). The derived keys are dropped
 * when the plugin tears down rather than living until the IDE exits.
 *
 * **Application-level, not project-level, and that is deliberate.** The master secret is
 * per-machine-and-per-user (see [PasswordSafeSecretStore]) and one student takes many
 * courses across many projects. A `Service.Level.PROJECT` cache would derive the same key
 * once per open project — more retained copies of the private key, for no benefit.
 *
 * ## The cache key includes a master-secret fingerprint
 *
 * Keyed on `courseId` PLUS a SHA-256 fingerprint of the master secret, never `courseId`
 * alone. A student who imports a different master secret mid-session — moving machines, or
 * correcting a bad paste — must not keep receiving keys derived from the old one: that would
 * silently produce a countersignature that cannot verify against the token they hold, and a
 * signature that does not verify is indistinguishable from tampering during an adjudication.
 * The fingerprint is a hash, so the cache key never contains the secret itself.
 *
 * ## Never throws
 *
 * [get] returns null on every failure. It is called from the session-start path, where the
 * only correct response to "cannot produce a key" is to record without an identity
 * (`SessionIdentityBuilder`'s rule 1). A caching layer must never be the reason a student's
 * work goes unrecorded.
 */
@Service(Service.Level.APP)
class CourseKeyCache @JvmOverloads constructor(
    /**
     * Derivation seam. Production uses `core`'s [deriveCourseKeypair]; tests inject a counting
     * or throwing stand-in to observe hits and failure handling. The no-arg overload
     * `@JvmOverloads` generates is what the platform's service container instantiates.
     */
    private val derive: (ByteArray, String) -> StudentCourseKeypair = ::deriveCourseKeypair,
) : Disposable {

    /** `"<master fingerprint>:<course_id>"` -> keypair. Guarded by [lock]. */
    private val entries = HashMap<String, StudentCourseKeypair>()

    /**
     * One lock over both [entries] and [disposed]. Session start runs off the EDT and more
     * than one project can activate concurrently, so this is genuinely contended; an
     * unsynchronized HashMap here would be a data race on a path that produces signing keys.
     */
    private val lock = Any()

    private var disposed = false

    /**
     * Derive (or return the cached) per-course keypair. Never throws.
     *
     * @param masterSecret the student's raw master secret bytes
     * @param courseId     the course to derive for; must be non-empty
     * @return the keypair, or null if the inputs are unusable or derivation failed
     */
    fun get(masterSecret: ByteArray, courseId: String): StudentCourseKeypair? {
        if (courseId.isEmpty()) return null

        val key = try {
            Sha256.hex(masterSecret) + ":" + courseId
        } catch (_: Throwable) {
            return null
        }

        synchronized(lock) {
            if (!disposed) entries[key]?.let { return it }
        }

        // Derivation runs OUTSIDE the lock: it is pure and side-effect free, so the worst case
        // of a concurrent miss on the same key is deriving twice and storing the same value —
        // strictly better than holding a lock across a crypto operation.
        val derived = try {
            derive(masterSecret, courseId)
        } catch (_: Throwable) {
            return null
        }

        synchronized(lock) {
            // A disposed cache still derives — callers must keep working — it simply stops
            // retaining, so no private key outlives teardown.
            if (!disposed) entries[key] = derived
        }
        return derived
    }

    /**
     * Drop every derived key. Idempotent. After this the cache still answers [get]
     * correctly; it just retains nothing.
     */
    override fun dispose() {
        synchronized(lock) {
            disposed = true
            entries.clear()
        }
    }

    /** Retained entry count. Test/inspection seam only. */
    val size: Int get() = synchronized(lock) { entries.size }
}
