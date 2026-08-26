package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [resolveEnrollmentPolicy] resolution cases. Mirrors the shape of
 * `PolicyGateKeyTest`/the `capture-policy.json` conformance cases for
 * [resolveCapturePolicy], but hand-written rather than vector-driven: the TS side
 * of this feature (`log-core`'s `policy.ts`) had not landed yet when this port was
 * written, so there is no exported vector to pin against. Whoever adds one later
 * should retire these in favour of it.
 */
class EnrollmentPolicyTest {

    @Test
    fun `no policy block at all resolves to required`() {
        assertEquals(DEFAULT_ENROLLMENT_POLICY, resolveEnrollmentPolicy(null))
        assertTrue(resolveEnrollmentPolicy(null).required)
    }

    @Test
    fun `a policy block with no enrollment key resolves to required`() {
        val block = buildJsonObject { putJsonObject("capture") { put("terminal", true) } }
        assertEquals(DEFAULT_ENROLLMENT_POLICY, resolveEnrollmentPolicy(block))
    }

    @Test
    fun `an enrollment block with no required key resolves to required`() {
        val block = buildJsonObject { putJsonObject("enrollment") {} }
        assertEquals(DEFAULT_ENROLLMENT_POLICY, resolveEnrollmentPolicy(block))
    }

    @Test
    fun `required false resolves to false`() {
        val block = buildJsonObject { putJsonObject("enrollment") { put("required", false) } }
        assertFalse(resolveEnrollmentPolicy(block).required)
    }

    @Test
    fun `required true resolves to true, explicitly`() {
        val block = buildJsonObject { putJsonObject("enrollment") { put("required", true) } }
        assertTrue(resolveEnrollmentPolicy(block).required)
    }

    @Test
    fun `a malformed non-boolean value falls back to required`() {
        // A string, a number, and an array all fail the same way: not a boolean,
        // so the safe default wins rather than any attempt to coerce it.
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":{"required":"false"}}""")).required)
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":{"required":0}}""")).required)
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":{"required":[]}}""")).required)
    }

    @Test
    fun `an explicit JSON null value falls back to required`() {
        val block = buildJsonObject { putJsonObject("enrollment") { put("required", JsonNull) } }
        assertTrue(resolveEnrollmentPolicy(block).required)
    }

    @Test
    fun `enrollment itself being the wrong shape falls back to required`() {
        // enrollment is a string / number / array / null instead of an object.
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":"off"}""")).required)
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":null}""")).required)
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("""{"enrollment":[]}""")).required)
    }

    @Test
    fun `the whole policy block being the wrong shape falls back to required`() {
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement(""""not an object"""")).required)
        assertTrue(resolveEnrollmentPolicy(Json.parseToJsonElement("[1,2,3]")).required)
    }

    @Test
    fun `capture and enrollment resolve independently of each other`() {
        val block = Json.parseToJsonElement(
            """{"capture":{"terminal":false},"enrollment":{"required":false}}""",
        ).jsonObject
        assertFalse(resolveCapturePolicy(block).terminal)
        assertFalse(resolveEnrollmentPolicy(block).required)
        assertTrue(resolveCapturePolicy(block).selectionChange)
    }
}
