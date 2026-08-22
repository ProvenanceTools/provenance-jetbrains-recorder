package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gate keys are a CLOSED set, and every member of it is wired into
 * [isEventKindCaptured].
 *
 * [isEventKindCaptured] used to end in `else -> true`. That branch made a typo in a
 * [POLICY_GATED_EVENT_KINDS] value silently CAPTURE an event the course had switched
 * off — the one direction a capture policy must never fail in, because a professor
 * turning capture down is the whole point of the block. log-core catches that at
 * compile time with `EventKind` + `satisfies`; this port now catches it the same way,
 * by making the gate key a type ([PolicyGateKey]) so the `when` is exhaustive and needs
 * no `else`.
 *
 * These tests cover what the type cannot: that every declared key is REACHABLE (a key
 * no event kind maps to is dead surface), and that each one actually gates the events
 * it claims to. There is deliberately no runtime throw on the emit path — dropping
 * recording would be a worse failure than the bug this shape prevents.
 */
class PolicyGateKeyTest {

    @Test
    fun `every gate key is used by at least one event kind`() {
        val used = POLICY_GATED_EVENT_KINDS.values.toSet()
        assertEquals(PolicyGateKey.entries.toSet(), used)
    }

    @Test
    fun `wire names are the three policy-capture booleans, and nothing else`() {
        assertEquals(
            listOf("focus_change", "selection_change", "terminal"),
            PolicyGateKey.entries.map { it.wireName }.sorted(),
        )
    }

    @Test
    fun `wire names are distinct`() {
        assertEquals(PolicyGateKey.entries.size, PolicyGateKey.entries.map { it.wireName }.toSet().size)
    }

    @Test
    fun `each gate key switches exactly the kinds mapped to it`() {
        for (key in PolicyGateKey.entries) {
            // A policy with only this key off.
            val policy = CapturePolicy(
                selectionChange = key != PolicyGateKey.SELECTION_CHANGE,
                focusChange = key != PolicyGateKey.FOCUS_CHANGE,
                terminal = key != PolicyGateKey.TERMINAL,
                heartbeatIntervalMs = DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs,
            )
            for ((kind, gate) in POLICY_GATED_EVENT_KINDS) {
                assertEquals(
                    gate != key,
                    isEventKindCaptured(kind, policy),
                    "$kind under only ${key.wireName} disabled",
                )
            }
        }
    }

    @Test
    fun `an event kind this build has never heard of stays on the floor`() {
        // Unknown EVENT KINDS fail open — the `null` branch, matching log-core. Only
        // unknown GATE KEYS are impossible, and they are impossible by type.
        val allOff = CapturePolicy(
            selectionChange = false,
            focusChange = false,
            terminal = false,
            heartbeatIntervalMs = DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs,
        )
        assertTrue(isEventKindCaptured("some.future.kind", allOff))
        assertTrue(isEventKindCaptured("", allOff))
        // …while a kind that IS gated is genuinely off under the same policy, so the
        // above is not just "everything returns true".
        assertFalse(isEventKindCaptured("selection.change", allOff))
    }

    @Test
    fun `no floor kind carries a gate key`() {
        for (kind in FLOOR_EVENT_KINDS) {
            assertFalse(kind in POLICY_GATED_EVENT_KINDS, "$kind is on the floor and must have no gate")
        }
    }
}
