package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Manifest
import dev.provenance.recorder.identity.IdentityOutcome
import dev.provenance.recorder.identity.IdentitySkipReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.nio.file.Paths

class RecorderStateTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            project.service<RecorderState>().deactivateAll()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(assignmentId: String = "hw03") =
        Manifest(assignmentId, "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))

    private fun root(name: String): Path = Paths.get("/ws-$name")

    /**
     * A manifest carrying `policy.enrollment.required = <required>`, or none of that
     * block at all when [required] is null. No `course_cert`/signature needed:
     * [RecorderState] never verifies a manifest, it only stores whatever
     * [dev.provenance.recorder.activation.ManifestActivation.Active] already verified —
     * so the resolution under test ([dev.provenance.recorder.activation.resolveVerifiedEnrollmentPolicy])
     * only ever looks at `format_version` and `policy`.
     */
    private fun manifestWithEnrollment(
        required: Boolean?,
        formatVersion: String? = "2.0",
        assignmentId: String = "hw03",
    ): Manifest {
        val policyJson = required?.let { """{"enrollment":{"required":$it}}""" }
        return Manifest(
            assignmentId = assignmentId,
            semester = "fa26",
            issuedAt = "2026-09-15T00:00:00Z",
            filesUnderReview = listOf("hw03.py"),
            sig = "a".repeat(128),
            formatVersion = formatVersion,
            policy = policyJson?.let { Json.parseToJsonElement(it).jsonObject },
        )
    }

    fun `test isActive is false by default`() {
        assertFalse(project.service<RecorderState>().isActive)
    }

    fun `test activate then isActive is true, manifest is stored`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        assertTrue(state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)
    }

    fun `test deactivateAll clears every manifest`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.deactivateAll()
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    fun `test deactivate one root leaves the other active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        state.deactivate(root("a"))
        assertTrue(state.isActive)
        assertEquals(setOf("hw-b"), state.activeManifests.values.map { it.assignmentId }.toSet())
    }

    fun `test manifest is null when more than one assignment is active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        assertNull("manifest is the single-assignment convenience; ambiguous with 2 active", state.manifest)
        assertEquals(2, state.activeManifests.size)
    }

    fun `test markDegraded keeps the root active and records the reason`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "NotImplementedError: an operation is not implemented")
        assertTrue("a degraded root stays activated (the indicator must keep rendering)", state.isActive)
        assertTrue(state.isDegraded(root("a")))
        assertEquals(
            "NotImplementedError: an operation is not implemented",
            state.degradedRoots[root("a").normalize()],
        )
    }

    fun `test re-activating a root clears its stale degraded marker`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "boom")
        state.activate(root("a"), manifest())
        assertFalse("a fresh activation supersedes the previous failure", state.isDegraded(root("a")))
        assertTrue(state.degradedRoots.isEmpty())
    }

    fun `test deactivate clears the degraded marker for that root only`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        state.markDegraded(root("a"), "boom")
        state.markDegraded(root("b"), "boom")
        state.deactivate(root("a"))
        assertFalse(state.isDegraded(root("a")))
        assertTrue(state.isDegraded(root("b")))
    }

    fun `test deactivateAll clears every degraded marker`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.markDegraded(root("a"), "boom")
        state.deactivateAll()
        assertTrue(state.degradedRoots.isEmpty())
    }

    fun `test activity activates state for every discovered root when discoverer returns Active manifests`() = runBlocking {
        val m = myFixture.addFileToProject("hw07/.provenance-manifest", "{}").virtualFile.parent
        val activity = RecorderActivationActivity { _, _ -> listOf(DiscoveredManifest(m, manifest("hw07"))) }
        activity.execute(project)
        val state = project.service<RecorderState>()
        assertTrue(state.isActive)
        assertEquals("hw07", state.manifest?.assignmentId)
    }

    fun `test a root with no resolvable filesystem path is activated but marked degraded`() = runBlocking {
        // A discovered root whose VirtualFile has no nio path (the light fixture's in-memory VFS
        // here; a non-local project root in production) is activated but can NEVER record —
        // activation only attempts a session start for a root it can resolve. Left unmarked it
        // rendered the NORMAL "recording" indicator while nothing was ever written: the exact
        // active-but-silent failure the degraded indicator exists to eliminate.
        val m = myFixture.addFileToProject("hw09/.provenance-manifest", "{}").virtualFile.parent
        assertNull(
            "guard: this case is vacuous if the fixture's root DOES resolve to an nio path",
            runCatching { m.toNioPath() }.getOrNull(),
        )
        val activity = RecorderActivationActivity { _, _ -> listOf(DiscoveredManifest(m, manifest("hw09"))) }

        activity.execute(project)

        val state = project.service<RecorderState>()
        val key = Paths.get(m.path)
        assertTrue("the root must stay activated so an indicator still renders", state.isActive)
        assertTrue("a root that can never record must not read as recording", state.isDegraded(key))
        assertNotNull("the degraded reason must say why", state.degradedRoots[key.normalize()])
    }

    fun `test activity leaves state inactive when discoverer returns nothing`() = runBlocking {
        val state = project.service<RecorderState>()
        state.activate(root("stale"), manifest())
        val activity = RecorderActivationActivity { _, _ -> emptyList() }
        activity.execute(project)
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    // -----------------------------------------------------------------------
    // identitySessions — the enrollment-policy tagging (program spec's
    // "enrollment not required" flag). Unlike an earlier version of this getter,
    // it does NOT drop waived roots — see EnrollNudge.isUnenrolled's KDoc for why a
    // wholesale filter here would misdiagnose a legacy 2.0 holder. It tags every
    // started root with its resolved `enrollmentRequired`, and leaves the
    // asymmetric filtering to isUnenrolled/shouldShowNudge/identitySuffix.
    // -----------------------------------------------------------------------

    private fun emittedIdentity(): IdentityOutcome = dev.provenance.recorder.identity.buildSessionIdentity(
        dev.provenance.recorder.identity.EnrollmentFixtures.manifest(),
        "20".repeat(32),
        "2026-09-08T12:00:00Z",
        dev.provenance.recorder.identity.InstitutionFixtures.credentialedStore(),
        null,
        dev.provenance.recorder.identity.InstitutionFixtures.rootPubkeyHex,
    ).also { check(it is IdentityOutcome.Emitted) { "fixture no longer emits: $it" } }

    fun `test identitySessions tags a root whose course does not require enrollment`() {
        val state = project.service<RecorderState>()
        state.activate(root("optout"), manifestWithEnrollment(required = false))
        state.recordIdentity(root("optout"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61a")))
        val session = state.identitySessions.single()
        assertFalse(session.enrollmentRequired)
    }

    fun `test identitySessions tags a root whose course requires enrollment`() {
        val state = project.service<RecorderState>()
        state.activate(root("required"), manifestWithEnrollment(required = true))
        state.recordIdentity(root("required"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61b")))
        val session = state.identitySessions.single()
        assertTrue(session.enrollmentRequired)
    }

    fun `test identitySessions tags a root with no enrollment key at all as required (default)`() {
        val state = project.service<RecorderState>()
        state.activate(root("default"), manifestWithEnrollment(required = null))
        state.recordIdentity(root("default"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61c")))
        val session = state.identitySessions.single()
        assertTrue(session.enrollmentRequired)
    }

    /**
     * The mixed case from the design brief: a student with one opted-out course and
     * one ordinary course open still gets nudged, because
     * [dev.provenance.recorder.identity.isUnenrolled] scopes "does anyone still
     * need to enrol" to requiring roots only, and the requiring root's skip
     * survives that scoping untouched.
     */
    fun `test a mixed project still nudges for the root that requires enrollment`() {
        val state = project.service<RecorderState>()
        state.activate(root("optout"), manifestWithEnrollment(required = false, assignmentId = "hw-a"))
        state.recordIdentity(root("optout"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61a")))
        state.activate(root("required"), manifestWithEnrollment(required = true, assignmentId = "hw-b"))
        state.recordIdentity(root("required"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61b")))

        assertEquals(2, state.identitySessions.size)
        assertTrue(dev.provenance.recorder.identity.isUnenrolled(state.identitySessions))
        assertTrue(
            dev.provenance.recorder.identity.shouldShowNudge(
                state.identitySessions,
                dev.provenance.recorder.identity.NudgeState.UNSEEN,
            ),
        )
    }

    /**
     * MANDATORY regression (code review correction): a legacy 2.0 holder attributed
     * through a WAIVED course, with a DIFFERENT, enrollment-requiring course also
     * open and skipped for `not_enrolled`, must not be reported un-enrolled. A
     * version of `identitySessions` that pre-filters waived roots before the
     * emitted-check would drop the waived root's Emitted outcome and misdiagnose
     * this exact student — see EnrollNudge.isUnenrolled's KDoc.
     */
    fun `test a student attributed through a waived course is not reported un-enrolled`() {
        val state = project.service<RecorderState>()
        state.activate(root("optout"), manifestWithEnrollment(required = false, assignmentId = "hw-a"))
        state.recordIdentity(root("optout"), emittedIdentity())
        state.activate(root("required"), manifestWithEnrollment(required = true, assignmentId = "hw-b"))
        state.recordIdentity(root("required"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61b")))

        assertEquals(2, state.identitySessions.size)
        assertFalse(dev.provenance.recorder.identity.isUnenrolled(state.identitySessions))
        assertFalse(
            dev.provenance.recorder.identity.shouldShowNudge(
                state.identitySessions,
                dev.provenance.recorder.identity.NudgeState.UNSEEN,
            ),
        )
    }

    /**
     * MANDATORY: a 1.x manifest cannot switch enrollment off (policy is not in its
     * signed payload), so its root must keep counting toward the un-enrolled
     * calculation even if something stapled `enrollment.required = false` onto it.
     */
    fun `test a 1x manifest ignores a stapled required false and still counts`() {
        val state = project.service<RecorderState>()
        state.activate(root("legacy"), manifestWithEnrollment(required = false, formatVersion = null))
        state.recordIdentity(root("legacy"), IdentityOutcome.Skipped(IdentitySkipReason.ManifestNot20))
        val session = state.identitySessions.single()
        assertTrue(session.enrollmentRequired)
    }

    fun `test deactivate removes an opted-out root from both maps together`() {
        val state = project.service<RecorderState>()
        state.activate(root("optout"), manifestWithEnrollment(required = false))
        state.recordIdentity(root("optout"), IdentityOutcome.Skipped(IdentitySkipReason.NotEnrolled("cs61a")))
        state.deactivate(root("optout"))
        assertTrue(state.identitySessions.isEmpty())
        assertFalse(state.isActive)
    }
}
