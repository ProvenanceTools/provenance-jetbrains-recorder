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
        assertEquals("", identitySuffix(listOf(emitted())))
        assertEquals(emptyList<String>(), identityTooltipLines(listOf(emitted())))
    }

    @Test
    fun `the not-enrolled wording is untouched`() {
        val outcomes = listOf(skipped(IdentitySkipReason.NotEnrolled("cs61b")))
        assertEquals(" (not enrolled)", identitySuffix(outcomes))
        assertEquals(listOf(enrollmentTooltipLine(true)), identityTooltipLines(outcomes))
    }

    @Test
    fun `a non-enrollment failure is visible in the bar and explained in the tooltip`() {
        // The reported bug, exactly: the status bar read plain "recording" and the bundle
        // came out unattributed with nothing said anywhere.
        val outcomes = listOf(skipped(IdentitySkipReason.StudentKeyMismatch("aa", "bb")))
        assertEquals(" (identity unavailable)", identitySuffix(outcomes))
        assertEquals(
            listOf(identitySkipAdvice(IdentitySkipReason.StudentKeyMismatch("aa", "bb"))),
            identityTooltipLines(outcomes),
        )
    }

    @Test
    fun `anyIdentityEmitted still wins over every skip`() {
        // The all-or-nothing rule in anyIdentityEmitted's docstring: one attributed session
        // makes "not enrolled" / "identity unavailable" the wrong thing to say.
        val mixed = listOf(
            emitted(),
            skipped(IdentitySkipReason.NotEnrolled("cs61b")),
            skipped(IdentitySkipReason.MasterSecretUnavailable("locked")),
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
        val outcomes = listOf(
            skipped(IdentitySkipReason.MasterSecretUnavailable("locked")),
            skipped(IdentitySkipReason.NotEnrolled("cs61b")),
            skipped(IdentitySkipReason.NotEnrolled("cs61c")),
            skipped(IdentitySkipReason.MasterSecretUnavailable("locked")),
        )
        val lines = identityTooltipLines(outcomes)
        assertEquals(2, lines.size)
        assertEquals(enrollmentTooltipLine(true), lines.first())
    }

    @Test
    fun `the tooltip does not depend on the order the sessions happened to report in`() {
        val outcomes = listOf(
            skipped(IdentitySkipReason.NoRootPublicKey),
            skipped(IdentitySkipReason.MasterSecretUnavailable("locked")),
            skipped(IdentitySkipReason.InvalidSessionPubkey),
        )
        // RecorderState hands these over from a ConcurrentHashMap, whose iteration order is
        // not the insertion order — a tooltip that reshuffles between refreshes is a bug.
        assertEquals(identityTooltipLines(outcomes), identityTooltipLines(outcomes.reversed()))
        assertEquals(3, identityTooltipLines(outcomes).size)
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
    // sessionsRequiringEnrollment — the multi-root enrollment-policy filter
    // ---------------------------------------------------------------------

    @Test
    fun `a session whose course does not require enrollment is dropped`() {
        val tracked = listOf(
            EnrollmentTrackedSession(skipped(IdentitySkipReason.NotEnrolled("cs61a")), enrollmentRequired = false),
        )
        assertEquals(emptyList<IdentityOutcome>(), sessionsRequiringEnrollment(tracked))
    }

    @Test
    fun `a session whose course requires enrollment survives the filter`() {
        val outcome = skipped(IdentitySkipReason.NotEnrolled("cs61b"))
        val tracked = listOf(EnrollmentTrackedSession(outcome, enrollmentRequired = true))
        assertEquals(listOf(outcome), sessionsRequiringEnrollment(tracked))
    }

    @Test
    fun `a mixed project — one opted-out course does not suppress the other course's nudge`() {
        val optedOut = skipped(IdentitySkipReason.NotEnrolled("cs61a"))
        val requiring = skipped(IdentitySkipReason.NotEnrolled("cs61b"))
        val filtered = sessionsRequiringEnrollment(
            listOf(
                EnrollmentTrackedSession(optedOut, enrollmentRequired = false),
                EnrollmentTrackedSession(requiring, enrollmentRequired = true),
            ),
        )
        assertEquals(listOf(requiring), filtered)
        // The existing all-or-nothing logic, unmodified, still nudges: the
        // requiring course's outcome reached it exactly as if the opted-out root
        // were not open at all.
        assertTrue(isUnenrolled(filtered))
        assertTrue(shouldShowNudge(filtered, NudgeState.UNSEEN))
    }

    @Test
    fun `a student with only an opted-out course sees nothing`() {
        val filtered = sessionsRequiringEnrollment(
            listOf(
                EnrollmentTrackedSession(
                    skipped(IdentitySkipReason.NotEnrolled("cs61a")),
                    enrollmentRequired = false,
                ),
            ),
        )
        assertEquals(emptyList<IdentityOutcome>(), filtered)
        assertFalse(isUnenrolled(filtered))
        assertFalse(shouldShowNudge(filtered, NudgeState.UNSEEN))
        assertEquals("", identitySuffix(filtered))
        assertEquals(emptyList<String>(), identityTooltipLines(filtered))
    }

    @Test
    fun `an emitted session from an opted-out course still counts as emitted if it survives (it does not)`() {
        // Emitted outcomes are unaffected by enrollment policy at the identity-builder
        // level (buildSessionIdentity never consults it) — but the FILTER applies to
        // Emitted outcomes exactly like Skipped ones, because the filter's whole job is
        // to decide which roots are even IN the multi-root calculation, not to inspect
        // what each root emitted.
        val emittedFromOptOutCourse = EnrollmentTrackedSession(emitted(), enrollmentRequired = false)
        assertEquals(emptyList<IdentityOutcome>(), sessionsRequiringEnrollment(listOf(emittedFromOptOutCourse)))
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
