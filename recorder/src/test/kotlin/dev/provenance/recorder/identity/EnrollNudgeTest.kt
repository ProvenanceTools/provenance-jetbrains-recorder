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
     * Wrap an outcome as a session whose course requires enrollment — the ordinary case
     * every test in this file that predates the waiver was written against. Most of this
     * file uses this rather than a bare [IdentityOutcome], because [isUnenrolled] and
     * friends now read [EnrollmentTrackedSession]s.
     */
    private fun required(outcome: IdentityOutcome): EnrollmentTrackedSession =
        EnrollmentTrackedSession(outcome, enrollmentRequired = true)

    /** Wrap an outcome as a session whose course has waived enrollment. */
    private fun waived(outcome: IdentityOutcome): EnrollmentTrackedSession =
        EnrollmentTrackedSession(outcome, enrollmentRequired = false)

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
        assertFalse(isUnenrolled(listOf(required(emitted()))))
        assertTrue(anyIdentityEmitted(listOf(emitted(), skipped(IdentitySkipReason.NotEnrolled("x")))))
    }

    @Test
    fun `one emitting root of several is enough`() {
        assertFalse(isUnenrolled(listOf(required(skipped(IdentitySkipReason.NotEnrolled("x"))), required(emitted()))))
    }

    @Test
    fun `every session skipped for want of a credential reads as un-enrolled`() {
        assertTrue(isUnenrolled(listOf(required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))))))
        assertTrue(isUnenrolled(listOf(required(skipped(IdentitySkipReason.ManifestNot20)))))
    }

    @Test
    fun `a broken keyring is not an enrollment problem`() {
        assertFalse(isUnenrolled(listOf(required(skipped(IdentitySkipReason.NoRootPublicKey)))))
        assertFalse(isUnenrolled(listOf(required(skipped(IdentitySkipReason.MasterSecretUnavailable("locked"))))))
    }

    @Test
    fun `a broken root alongside an un-enrolled one still nudges`() {
        assertTrue(
            isUnenrolled(
                listOf(
                    required(skipped(IdentitySkipReason.NoRootPublicKey)),
                    required(skipped(IdentitySkipReason.NotEnrolled("cs61c"))),
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
    // identitySkipAdvice — the OTHER eight reasons, which used to say nothing
    // ---------------------------------------------------------------------

    /** The names a student must never be shown. */
    private val reasonClassNames: List<String> = allSkipReasons.mapNotNull { it::class.simpleName }

    @Test
    fun `every skip reason yields advice, and none of it leaks the enum name`() {
        for (reason in allSkipReasons) {
            val advice = identitySkipAdvice(reason)
            assertTrue("blank advice for $reason", advice.isNotBlank())
            for (name in reasonClassNames) {
                assertFalse("advice for $reason leaks \"$name\": $advice", advice.contains(name))
            }
        }
    }

    @Test
    fun `every skip reason names the consequence`() {
        // The whole defect: a student could not tell an unattributed session from an
        // attributed one. Whatever went wrong, the line has to say the work is unattributed.
        for (reason in allSkipReasons) {
            assertTrue(
                "advice for $reason does not state the consequence: ${identitySkipAdvice(reason)}",
                identitySkipAdvice(reason).contains("not attributed") ||
                    identitySkipAdvice(reason).contains("not be attributed"),
            )
        }
    }

    @Test
    fun `the two enrolling fixes keep the existing wording, verbatim`() {
        assertEquals(enrollmentTooltipLine(true), identitySkipAdvice(IdentitySkipReason.NotEnrolled("cs61b")))
        assertEquals(enrollmentTooltipLine(true), identitySkipAdvice(IdentitySkipReason.ManifestNot20))
    }

    @Test
    fun `the eight non-enrollment reasons each say something different`() {
        val others = allSkipReasons.filterNot { isUnenrolledSkip(it) }.map { identitySkipAdvice(it) }
        assertEquals(8, others.size)
        assertEquals("each reason needs its own advice", others.size, others.distinct().size)
    }

    @Test
    fun `a key mismatch sends the student to their secret, never to the enrollment page`() {
        // Enrolling again mints a credential for the SAME wrong key. The fix is the secret.
        for (reason in listOf(
            IdentitySkipReason.CredentialKeyMismatch("aa", "bb"),
            IdentitySkipReason.StudentKeyMismatch("aa", "bb"),
        )) {
            val advice = identitySkipAdvice(reason)
            assertTrue(advice, advice.contains("Import Student Identity Secret"))
            assertFalse(advice, advice.contains(ENROLL_URL))
        }
    }

    @Test
    fun `an unavailable keyring is not answered with advice that needs the keyring`() {
        val advice = identitySkipAdvice(IdentitySkipReason.MasterSecretUnavailable("SecretStoreUnavailable"))
        assertTrue(advice, advice.contains("credential store"))
        assertFalse("enrolling needs the same store", advice.contains(ENROLL_URL))
        assertTrue("the detail belongs in the line for staff", advice.contains("SecretStoreUnavailable"))
    }

    @Test
    fun `a broken build points at the plugin, not at the student`() {
        for (reason in listOf(
            IdentitySkipReason.NoRootPublicKey,
            IdentitySkipReason.InvalidSessionPubkey,
            IdentitySkipReason.UnexpectedError("boom"),
        )) {
            val advice = identitySkipAdvice(reason)
            assertTrue(advice, advice.contains("course staff"))
            assertFalse("enrolling cannot fix a broken build", advice.contains(ENROLL_URL))
        }
    }

    // ---------------------------------------------------------------------
    // identitySuffix / identityTooltipLines — what the widget renders
    // ---------------------------------------------------------------------

    @Test
    fun `an attributed session renders exactly as before`() {
        assertEquals("", identitySuffix(listOf(required(emitted()))))
        assertEquals(emptyList<String>(), identityTooltipLines(listOf(required(emitted()))))
    }

    @Test
    fun `the not-enrolled wording is untouched`() {
        val sessions = listOf(required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))))
        assertEquals(" (not enrolled)", identitySuffix(sessions))
        assertEquals(listOf(enrollmentTooltipLine(true)), identityTooltipLines(sessions))
    }

    @Test
    fun `a non-enrollment failure is visible in the bar and explained in the tooltip`() {
        // The reported bug, exactly: the status bar read plain "recording" and the bundle
        // came out unattributed with nothing said anywhere.
        val sessions = listOf(required(skipped(IdentitySkipReason.StudentKeyMismatch("aa", "bb"))))
        assertEquals(" (identity unavailable)", identitySuffix(sessions))
        assertEquals(
            listOf(identitySkipAdvice(IdentitySkipReason.StudentKeyMismatch("aa", "bb"))),
            identityTooltipLines(sessions),
        )
    }

    @Test
    fun `anyIdentityEmitted still wins over every skip`() {
        // The all-or-nothing rule in anyIdentityEmitted's docstring: one attributed session
        // makes "not enrolled" / "identity unavailable" the wrong thing to say.
        val mixed = listOf(
            required(emitted()),
            required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))),
            required(skipped(IdentitySkipReason.MasterSecretUnavailable("locked"))),
        )
        assertEquals("", identitySuffix(mixed))
        assertEquals(emptyList<String>(), identityTooltipLines(mixed))
    }

    @Test
    fun `no sessions means nothing to say`() {
        assertEquals("", identitySuffix(emptyList()))
        assertEquals(emptyList<String>(), identityTooltipLines(emptyList()))
    }

    @Test
    fun `the enrollment line leads, and repeated reasons are said once`() {
        val sessions = listOf(
            required(skipped(IdentitySkipReason.MasterSecretUnavailable("locked"))),
            required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))),
            required(skipped(IdentitySkipReason.NotEnrolled("cs61c"))),
            required(skipped(IdentitySkipReason.MasterSecretUnavailable("locked"))),
        )
        val lines = identityTooltipLines(sessions)
        assertEquals(2, lines.size)
        assertEquals(enrollmentTooltipLine(true), lines.first())
    }

    @Test
    fun `the tooltip does not depend on the order the sessions happened to report in`() {
        val sessions = listOf(
            required(skipped(IdentitySkipReason.NoRootPublicKey)),
            required(skipped(IdentitySkipReason.MasterSecretUnavailable("locked"))),
            required(skipped(IdentitySkipReason.InvalidSessionPubkey)),
        )
        // RecorderState hands these over from a ConcurrentHashMap, whose iteration order is
        // not the insertion order — a tooltip that reshuffles between refreshes is a bug.
        assertEquals(identityTooltipLines(sessions), identityTooltipLines(sessions.reversed()))
        assertEquals(3, identityTooltipLines(sessions).size)
    }

    // ---------------------------------------------------------------------
    // shouldShowNudge / nextNudgeState
    // ---------------------------------------------------------------------

    private val unenrolled = listOf(required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))))

    @Test
    fun `shows while unseen or intent, never once done`() {
        assertTrue(shouldShowNudge(unenrolled, NudgeState.UNSEEN))
        assertTrue(shouldShowNudge(unenrolled, NudgeState.INTENT))
        assertFalse(shouldShowNudge(unenrolled, NudgeState.DONE))
    }

    @Test
    fun `never shows to an enrolled student whatever the state`() {
        for (state in NudgeState.entries) {
            assertFalse(shouldShowNudge(listOf(required(emitted())), state))
        }
    }

    @Test
    fun `never shows for a failure enrolling cannot fix`() {
        assertFalse(shouldShowNudge(listOf(required(skipped(IdentitySkipReason.NoRootPublicKey))), NudgeState.UNSEEN))
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
    // Multi-root enrollment waiver — isUnenrolled's asymmetric split
    //
    // "Did anyone claim an identity?" reads EVERY session, waived roots included.
    // "Does anyone still need to enrol?" reads ONLY the roots that still require it.
    // See isUnenrolled's KDoc for why a single upfront filter over the session list
    // is wrong, not just simpler.
    // ---------------------------------------------------------------------

    @Test
    fun `a lone waived root is never un-enrolled, whatever its outcome`() {
        assertFalse(isUnenrolled(listOf(waived(skipped(IdentitySkipReason.NotEnrolled("cs61a"))))))
        assertFalse(isUnenrolled(listOf(waived(emitted()))))
    }

    @Test
    fun `a lone requiring root behaves exactly as before the waiver existed`() {
        assertTrue(isUnenrolled(listOf(required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))))))
        assertFalse(isUnenrolled(listOf(required(emitted()))))
    }

    @Test
    fun `two skipped roots, one waived — the requiring root's skip still nudges`() {
        // Both roots skipped; only the requiring one gets a vote on "does anyone still
        // need to enrol", and it votes yes.
        val sessions = listOf(
            waived(skipped(IdentitySkipReason.NotEnrolled("cs61a"))),
            required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))),
        )
        assertTrue(isUnenrolled(sessions))
        assertTrue(shouldShowNudge(sessions, NudgeState.UNSEEN))
    }

    /**
     * MANDATORY regression. A filter-first implementation (drop waived roots, THEN run
     * the ordinary all-or-nothing logic) would exclude the waived root's EMITTED
     * identity before the emitted-check ever ran, see only the requiring root's
     * `not_enrolled` skip, and report "not enrolled" about a student who IS
     * attributed through the waived course. `isUnenrolled` must read the
     * emitted-check over every session, waived roots included, and scope only the
     * "still needs to enrol" half to requiring roots — that asymmetry is what this
     * test pins.
     */
    @Test
    fun `a legacy 2 0 holder attributed through a waived course is NOT reported un-enrolled`() {
        val sessions = listOf(
            waived(emitted()),
            required(skipped(IdentitySkipReason.NotEnrolled("cs61b"))),
        )
        assertFalse(isUnenrolled(sessions))
        assertFalse(shouldShowNudge(sessions, NudgeState.UNSEEN))
        assertEquals("", identitySuffix(sessions))
        assertEquals(emptyList<String>(), identityTooltipLines(sessions))
    }

    @Test
    fun `a student with only a waived course sees nothing, however it failed`() {
        val sessions = listOf(waived(skipped(IdentitySkipReason.NotEnrolled("cs61a"))))
        assertFalse(isUnenrolled(sessions))
        assertFalse(shouldShowNudge(sessions, NudgeState.UNSEEN))
        assertEquals("", identitySuffix(sessions))
        assertEquals(emptyList<String>(), identityTooltipLines(sessions))
    }

    @Test
    fun `a waived root's non-enrollment failure does not surface identity-unavailable either`() {
        // The waiver is not narrowly scoped to the "not enrolled" wording — a course
        // that opted out of enrollment tracking has no claim on any of this module's
        // output for its own roots.
        val sessions = listOf(waived(skipped(IdentitySkipReason.NoRootPublicKey)))
        assertEquals("", identitySuffix(sessions))
        assertEquals(emptyList<String>(), identityTooltipLines(sessions))
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
