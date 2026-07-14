package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestTest {
    private val fixture by lazy {
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/manifest.json")!!.readText(),
        ).jsonObject
    }

    private val coursePubkey get() = fixture["course_pubkey_hex"]!!.jsonPrimitive.content
    private val manifestText get() = fixture["manifest"]!!.jsonObject.toString()

    @Test
    fun `parses a valid manifest`() {
        val r = parseManifest(manifestText)
        assertInstanceOf(ManifestParse.Ok::class.java, r)
        val m = (r as ManifestParse.Ok).manifest
        assertEquals("hw3", m.assignmentId)
        assertEquals("fa25", m.semester)
        assertEquals(listOf("src/main.py", "src/util.py"), m.filesUnderReview)
        assertEquals(128, m.sig.length)
    }

    @Test
    fun `cross-language verify accepts the log-core signed manifest`() {
        val m = (parseManifest(manifestText) as ManifestParse.Ok).manifest
        assertTrue(verifyManifest(m, coursePubkey))
    }

    @Test
    fun `verify rejects a mutated field`() {
        val m = (parseManifest(manifestText) as ManifestParse.Ok).manifest
        val tampered = m.copy(assignmentId = "hw4")
        assertFalse(verifyManifest(tampered, coursePubkey))
    }

    @Test
    fun `verify rejects a mutated files list`() {
        val m = (parseManifest(manifestText) as ManifestParse.Ok).manifest
        val tampered = m.copy(filesUnderReview = listOf("src/main.py"))
        assertFalse(verifyManifest(tampered, coursePubkey))
    }

    @Test
    fun `verify returns false on malformed pubkey`() {
        val m = (parseManifest(manifestText) as ManifestParse.Ok).manifest
        assertFalse(verifyManifest(m, "nothex"))
        assertFalse(verifyManifest(m, "ab"))
    }

    @Test
    fun `parse rejects non-object`() {
        assertInstanceOf(ManifestParse.Err::class.java, parseManifest("[1,2,3]"))
        assertInstanceOf(ManifestParse.Err::class.java, parseManifest("\"str\""))
    }

    @Test
    fun `parse rejects invalid json`() {
        assertInstanceOf(ManifestParse.Err::class.java, parseManifest("{not json"))
    }

    @Test
    fun `parse rejects missing field`() {
        val r = parseManifest("""{"assignment_id":"hw3","semester":"fa25","issued_at":"x","sig":"${"a".repeat(128)}"}""")
        assertInstanceOf(ManifestParse.Err::class.java, r)
    }

    @Test
    fun `parse rejects non-128-hex sig`() {
        val r = parseManifest(
            """{"assignment_id":"hw3","semester":"fa25","issued_at":"x","files_under_review":[],"sig":"abcd"}""",
        )
        assertInstanceOf(ManifestParse.Err::class.java, r)
    }

    @Test
    fun `parse rejects non-string files entry`() {
        val r = parseManifest(
            """{"assignment_id":"hw3","semester":"fa25","issued_at":"x","files_under_review":[1],"sig":"${"a".repeat(128)}"}""",
        )
        assertInstanceOf(ManifestParse.Err::class.java, r)
    }
}
