package dev.provenance.recorder.identity

import dev.provenance.core.IdentityChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `packages/recorder/src/activation/enroll-nudge.test.ts` in the VS Code recorder.
 */
class EnrollNudgeTest {

    /** Every skip reason the builder can return, so the partition below is exhaustive. */
    private val allSkipReasons: List<IdentitySkipReason> = listOf(
        IdentitySkipReason.NoRootPublicKey,
        IdentitySkipReason.InstitutionCertNotRootSigned,
        IdentitySkipReason.CredentialKeyMismatch("aa", "bb"),
        IdentitySkipReason.ManifestNot20,
        IdentitySkipReason.NotEnrolled("cs61b"),
        IdentitySkipReason.MasterSecretUnavailable("keyring_unavailable"),
        IdentitySkipReason.InvalidSessionPubkey,
        IdentitySkipReason.StudentKeyMismatch("aa", "bb"),
        IdentitySkipReason.ChainDidNotVerify(IdentityChain.InvalidSessionPubkeySignature),
        IdentitySkipReason.UnexpectedError("boom"),
    )

    private fun skipped(reason: IdentitySkipReason): IdentityOutcome = IdentityOutcome.Skipped(reason)

    /**
     * A real Emitted outcome, built through the real builder over real signatures — the same
     * route `InstitutionIdentityBuilderTest` uses. Only its tag matters here, but constructing
     * it honestly means a chain-walk regression shows up as a failure in this file too, rather
     * than as a stub that keeps claiming "enrolled".
     */
    private fun emitted(): IdentityOutcome = buildSessionIdentity(
        EnrollmentFixtures.manifest(),
        "20".repeat(32),
        "2026-09-08T12:00:00Z",
        InstitutionFixtures.credentialedStore(),
        null,
        InstitutionFixtures.rootPubkeyHex,
    ).also { check(it is IdentityOutcome.Emitted) { "fixture no longer emits: $it" } }

    // ---------------------------------------------------------------------
    // isUnenrolledSkip — the partition
    // ---------------------------------------------------------------------

    @Test
    fun `only the two reasons enrolling would fix are actionable`() {
        val actionable = allSkipReasons.filter { isUnenrolledSkip(it) }
        assertEquals(2, actionable.size)
        assertTrue(actionable.any { it is IdentitySkipReason.NotEnrolled })
        assertTrue(actionable.any { it is IdentitySkipReason.ManifestNot20 })
    }

    @Test
    fun `a broken build is never the student's fault`() {
        assertFalse(isUnenrolledSkip(IdentitySkipReason.NoRootPublicKey))
        assertFalse(isUnenrolledSkip(IdentitySkipReason.InstitutionCertNotRootSigned))
        assertFalse(isUnenrolledSkip(IdentitySkipReason.MasterSecretUnavailable("locked")))
        assertFalse(isUnenrolledSkip(IdentitySkipReason.InvalidSessionPubkey))
        assertFalse(isUnenrolledSkip(IdentitySkipReason.UnexpectedError("boom")))
    }

    @Test
    fun `a key mismatch means wrong machine, not missing enrollment`() {
        assertFalse(isUnenrolledSkip(IdentitySkipReason.CredentialKeyMismatch("aa", "bb")))
        assertFalse(isUnenrolledSkip(IdentitySkipReason.StudentKeyMismatch("aa", "bb")))
    }

    // ---------------------------------------------------------------------
    // isUnenrolled
    // ---------------------------------------------------------------------

    @Test
    fun `a legacy 2 0 holder is enrolled — they emit, so they are attributed`() {
        // The regression this file exists to avoid: a credential lookup would call this
        // student un-enrolled and tell them their work is unattributed. It is not.
        assertFalse(isUnenrolled(listOf(emitted())))
        assertTrue(anyIdentityEmitted(listOf(emitted(), skipped(IdentitySkipReason.NotEnrolled("x")))))
    }

    @Test
    fun `one emitting root of several is enough`() {
        assertFalse(isUnenrolled(listOf(skipped(IdentitySkipReason.NotEnrolled("x")), emitted())))
    }

    @Test
    fun `every session skipped for want of a credential reads as un-enrolled`() {
        assertTrue(isUnenrolled(listOf(skipped(IdentitySkipReason.NotEnrolled("cs61b")))))
        assertTrue(isUnenrolled(listOf(skipped(IdentitySkipReason.ManifestNot20))))
    }

    @Test
    fun `a broken keyring is not an enrollment problem`() {
        assertFalse(isUnenrolled(listOf(skipped(IdentitySkipReason.NoRootPublicKey))))
        assertFalse(isUnenrolled(listOf(skipped(IdentitySkipReason.MasterSecretUnavailable("locked")))))
    }

    @Test
    fun `a broken root alongside an un-enrolled one still nudges`() {
        assertTrue(
            isUnenrolled(
                listOf(
                    skipped(IdentitySkipReason.NoRootPublicKey),
                    skipped(IdentitySkipReason.NotEnrolled("cs61c")),
                ),
            ),
        )
    }

    @Test
    fun `no sessions is not un-enrolled`() {
        assertFalse(isUnenrolled(emptyList()))
    }

    // ---------------------------------------------------------------------
    // Status bar wording
    // ---------------------------------------------------------------------

    @Test
    fun `an enrolled student's widget is untouched`() {
        assertEquals("", enrollmentSuffix(false))
        assertNull(enrollmentTooltipLine(false))
    }

    @Test
    fun `an un-enrolled student is told the consequence and where to go`() {
        assertEquals(" (not enrolled)", enrollmentSuffix(true))
        val tip = enrollmentTooltipLine(true)!!
        assertTrue(tip.contains("not attributed"))
        assertTrue(tip.contains(ENROLL_URL))
    }

    @Test
    fun `the nudge names the consequence, not just the chore`() {
        assertTrue(NUDGE_MESSAGE.contains("not be attributed"))
    }

    // ---------------------------------------------------------------------
    // shouldShowNudge / nextNudgeState
    // ---------------------------------------------------------------------

    private val unenrolled = listOf(skipped(IdentitySkipReason.NotEnrolled("cs61b")))

    @Test
    fun `shows while unseen or intent, never once done`() {
        assertTrue(shouldShowNudge(unenrolled, NudgeState.UNSEEN))
        assertTrue(shouldShowNudge(unenrolled, NudgeState.INTENT))
        assertFalse(shouldShowNudge(unenrolled, NudgeState.DONE))
    }

    @Test
    fun `never shows to an enrolled student whatever the state`() {
        for (state in NudgeState.entries) {
            assertFalse(shouldShowNudge(listOf(emitted()), state))
        }
    }

    @Test
    fun `never shows for a failure enrolling cannot fix`() {
        assertFalse(shouldShowNudge(listOf(skipped(IdentitySkipReason.NoRootPublicKey)), NudgeState.UNSEEN))
    }

    @Test
    fun `dismissing is permanent from either live state`() {
        assertEquals(NudgeState.DONE, nextNudgeState(NudgeState.UNSEEN, NudgeAction.DISMISS))
        assertEquals(NudgeState.DONE, nextNudgeState(NudgeState.INTENT, NudgeAction.DISMISS))
    }

    @Test
    fun `intent buys exactly one follow-up`() {
        assertEquals(NudgeState.INTENT, nextNudgeState(NudgeState.UNSEEN, NudgeAction.ENROLL))
        assertEquals(NudgeState.INTENT, nextNudgeState(NudgeState.UNSEEN, NudgeAction.SHOW_KEY))
        assertEquals(NudgeState.DONE, nextNudgeState(NudgeState.INTENT, NudgeAction.ENROLL))
        assertEquals(NudgeState.DONE, nextNudgeState(NudgeState.INTENT, NudgeAction.SHOW_KEY))
    }

    @Test
    fun `done is terminal under every action`() {
        for (action in NudgeAction.entries) {
            assertEquals(NudgeState.DONE, nextNudgeState(NudgeState.DONE, action))
        }
    }

    @Test
    fun `caps lifetime notifications at two on the click-through path`() {
        // Ten un-enrolled sessions, the student clicking "Enroll" every time and never
        // finishing. The status bar keeps saying it; the popup must not.
        var state = NudgeState.UNSEEN
        var shown = 0
        repeat(10) {
            if (shouldShowNudge(unenrolled, state)) {
                shown++
                state = nextNudgeState(state, NudgeAction.ENROLL)
            }
        }
        assertEquals(2, shown)
        assertEquals(NudgeState.DONE, state)
    }

    @Test
    fun `caps at one when the student dismisses`() {
        var state = NudgeState.UNSEEN
        var shown = 0
        repeat(10) {
            if (shouldShowNudge(unenrolled, state)) {
                shown++
                state = nextNudgeState(state, NudgeAction.DISMISS)
            }
        }
        assertEquals(1, shown)
    }

    // ---------------------------------------------------------------------
    // NudgeState.parse
    // ---------------------------------------------------------------------

    @Test
    fun `parse round-trips the persisted names and treats anything else as fresh`() {
        assertEquals(NudgeState.INTENT, NudgeState.parse("INTENT"))
        assertEquals(NudgeState.DONE, NudgeState.parse("done"))
        assertEquals(NudgeState.UNSEEN, NudgeState.parse(null))
        assertEquals(NudgeState.UNSEEN, NudgeState.parse(""))
        assertEquals(NudgeState.UNSEEN, NudgeState.parse("nonsense"))
    }
}
