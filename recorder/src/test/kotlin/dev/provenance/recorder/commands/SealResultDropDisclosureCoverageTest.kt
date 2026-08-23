package dev.provenance.recorder.commands

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * A GUARD ON [SealResult.Ok]'S DOCSTRING PROMISE, not on today's behavior.
 *
 * [SealResult.Ok]'s docstring says every drop fact surfaces through [SealResult.Ok.anythingDropped]
 * and [SealResult.Ok.droppedDescriptions]. Kotlin's type system cannot enforce that for a plain
 * `Boolean` constructor property — nothing stops a future edit from adding an 11th flag and
 * forgetting the matching arm, which is exactly how this port lost one before. This suite closes
 * that hole at test time by REFLECTING over [SealResult.Ok]'s actual boolean fields, rather than
 * hand-listing them, so a newly added field is caught automatically -- no test-file edit required
 * to be covered by the guard, only to be exempted from it.
 *
 * Two kinds of boolean field exist on [SealResult.Ok]:
 *  - DROP FACTS: everything [SealResult.Ok.anythingDropped] ORs together and
 *    [SealResult.Ok.droppedDescriptions] has an arm for.
 *  - Everything else ([SealResult.Ok.chainBroken], [SealResult.Ok.unreadableSession]): integrity
 *    facts that deliberately feed a SEPARATE clause in `PrepareSubmissionBundleAction.notify` --
 *    see the comment there for why they must stay independent of the drop-disclosure clause.
 *    Listed explicitly in [KNOWN_NON_DROP_BOOLEAN_FIELDS] so excluding a field from this guard is
 *    always a conscious decision, never an accident.
 */
class SealResultDropDisclosureCoverageTest {

    private fun minimalOk(): SealResult.Ok = SealResult.Ok(
        bundlePath = Path.of("bundle.zip"),
        manifestSha256 = "e".repeat(64),
        chainBroken = false,
        unreadableSession = false,
    )

    /**
     * A fresh, otherwise-minimal [SealResult.Ok] with exactly the named boolean field flipped
     * to `true`. Uses [java.lang.reflect.Field] directly (no kotlin-reflect dependency): a Kotlin
     * data class constructor property compiles to a private, non-static, final instance field,
     * and `Field.set` on such a field is legal once [java.lang.reflect.Field.setAccessible] has
     * succeeded -- see the `Field.set` javadoc's final-field carve-out.
     */
    private fun withFlagTrue(fieldName: String): SealResult.Ok {
        val ok = minimalOk()
        val field = SealResult.Ok::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setBoolean(ok, true)
        return ok
    }

    @Test
    fun `every boolean drop-fact field on SealResult-Ok has a droppedDescriptions() arm`() {
        val booleanFieldNames = SealResult.Ok::class.java.declaredFields
            .filter { it.type == java.lang.Boolean.TYPE }
            .map { it.name }
            .toSet()

        // Guard the guard: if reflection ever stopped finding SealResult.Ok's boolean
        // constructor properties (a Kotlin/JVM codegen change), this must fail loudly rather
        // than passing vacuously over an empty set.
        assertTrue(
            "expected to find SealResult.Ok's boolean constructor properties via reflection, " +
                "found: $booleanFieldNames",
            booleanFieldNames.size > KNOWN_NON_DROP_BOOLEAN_FIELDS.size,
        )
        assertTrue(
            "KNOWN_NON_DROP_BOOLEAN_FIELDS names a field SealResult.Ok no longer has -- update " +
                "the exclusion list to match reality",
            booleanFieldNames.containsAll(KNOWN_NON_DROP_BOOLEAN_FIELDS),
        )

        val dropFactFieldNames = booleanFieldNames - KNOWN_NON_DROP_BOOLEAN_FIELDS
        for (name in dropFactFieldNames) {
            val flagged = withFlagTrue(name)
            assertTrue(
                "SealResult.Ok.$name has no droppedDescriptions() arm. Its docstring promises " +
                    "that EVERY drop is disclosed to the student -- add an arm in " +
                    "droppedDescriptions() and confirm this field is ORed into anythingDropped. " +
                    "If $name is NOT a drop fact (it feeds some OTHER clause, the way " +
                    "chainBroken and unreadableSession feed the separate integrity clause), " +
                    "add it to KNOWN_NON_DROP_BOOLEAN_FIELDS in this test instead.",
                flagged.anythingDropped && flagged.droppedDescriptions().isNotEmpty(),
            )
        }
    }

    private companion object {
        /**
         * Boolean [SealResult.Ok] properties that are deliberately NOT drop facts. Every other
         * boolean field discovered by reflection is asserted to have a [SealResult.Ok.droppedDescriptions]
         * arm; a field belongs here only when it genuinely reports something other than "this was
         * left out of the bundle" -- see the class doc above.
         */
        val KNOWN_NON_DROP_BOOLEAN_FIELDS = setOf("chainBroken", "unreadableSession")
    }
}
