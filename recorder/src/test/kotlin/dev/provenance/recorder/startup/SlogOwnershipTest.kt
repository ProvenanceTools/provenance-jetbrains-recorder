package dev.provenance.recorder.startup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership classification + eligible-selection, exercised directly. Read-only by
 * construction: [selectEligible] is handed a read function and nothing else, so no test
 * here can observe a rename — there is no rename reachable from this module.
 */
class SlogOwnershipTest {
    private val dir = "/prov"

    /** First line only; that is all [selectEligible] reads. */
    private fun head(wall: String, studentRef: String? = null): String {
        val identity =
            if (studentRef == null) "" else ""","identity":{"enrollment":{"student_ref":"$studentRef"}}"""
        return """{"seq":0,"t":0,"wall":"$wall","kind":"session.start",""" +
            """"data":{"session_id":"s"$identity}}""" + "\n"
    }

    private fun reader(files: Map<String, SlogReadResult>): suspend (String) -> SlogReadResult =
        { path -> files[path] ?: SlogReadResult.Err("not_found") }

    // -----------------------------------------------------------------------
    // classifySlogOwnership — the three-class table
    // -----------------------------------------------------------------------

    @Test
    fun `equal refs are own`() {
        assertEquals(SlogOwnership.Own, classifySlogOwnership("alice", "alice"))
    }

    @Test
    fun `different refs are foreign`() {
        assertEquals(SlogOwnership.Foreign, classifySlogOwnership("alice", "bob"))
    }

    @Test
    fun `a candidate naming nobody is unattributed whoever we are`() {
        assertEquals(SlogOwnership.Unattributed, classifySlogOwnership("alice", null))
        assertEquals(SlogOwnership.Unattributed, classifySlogOwnership(null, null))
    }

    @Test
    fun `we have no ref and the candidate has one - foreign, not unattributed`() {
        // Asymmetric on purpose: we cannot claim to be a contributor we cannot name, and
        // misfiling a partner's log as ours destroys it while misfiling ours as theirs
        // only costs a back-pointer.
        assertEquals(SlogOwnership.Foreign, classifySlogOwnership(null, "bob"))
    }

    // -----------------------------------------------------------------------
    // isEligible
    // -----------------------------------------------------------------------

    @Test
    fun `own is always eligible and foreign never is`() {
        assertTrue(isEligible(SlogOwnership.Own, "alice"))
        assertTrue(isEligible(SlogOwnership.Own, null))
        assertFalse(isEligible(SlogOwnership.Foreign, "alice"))
        assertFalse(isEligible(SlogOwnership.Foreign, null))
    }

    @Test
    fun `unattributed is eligible only for an unattributed recorder`() {
        assertTrue(isEligible(SlogOwnership.Unattributed, null))
        assertFalse(isEligible(SlogOwnership.Unattributed, "alice"))
    }

    // -----------------------------------------------------------------------
    // selectEligible — wall order, ties, fallback, foreign exclusion
    // -----------------------------------------------------------------------

    @Test
    fun `picks the latest session start wall, not the alphabetically last filename`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Ok(head("2026-07-14T11:00:00.000Z", "alice")),
            "$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T09:00:00.000Z", "alice")),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), "alice")
        assertEquals("a.slog", pick?.filename)
    }

    @Test
    fun `equal walls tie-break on filename descending`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Ok(head("2026-07-14T11:00:00.000Z", "alice")),
            "$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T11:00:00.000Z", "alice")),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), "alice")
        assertEquals("z.slog", pick?.filename)
    }

    @Test
    fun `a foreign file is skipped even when it has the latest wall`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Ok(head("2026-07-14T09:00:00.000Z", "alice")),
            "$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T23:00:00.000Z", "bob")),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), "alice")
        assertEquals("a.slog", pick?.filename)
    }

    @Test
    fun `nothing eligible yields null so the caller starts clean and touches nothing`() = runBlocking {
        val files = mapOf("$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T23:00:00.000Z", "bob")))
        assertNull(selectEligible(listOf("z.slog"), dir, reader(files), "alice"))
    }

    @Test
    fun `an unreadable file is unattributed, so an enrolled recorder is not offered it`() = runBlocking {
        val files = mapOf("$dir/z.slog" to SlogReadResult.Err("read_error"))
        assertNull(selectEligible(listOf("z.slog"), dir, reader(files), "alice"))
    }

    @Test
    fun `with nothing parseable the fallback is the alphabetically last eligible, text null`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Err("read_error"),
            "$dir/z.slog" to SlogReadResult.Ok("{ not json\n"),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), null)
        assertEquals("z.slog", pick?.filename)
        assertNull("fallback carries no text; the caller re-reads", pick?.text)
    }

    @Test
    fun `the selected file's text comes back so the caller need not re-read it`() = runBlocking {
        val text = head("2026-07-14T11:00:00.000Z", "alice")
        val files = mapOf("$dir/a.slog" to SlogReadResult.Ok(text))
        val pick = selectEligible(listOf("a.slog"), dir, reader(files), "alice")
        assertEquals(text, pick?.text)
    }

    @Test
    fun `a first line that is not session_start yields no wall, so it is only a fallback`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Ok("""{"seq":0,"t":0,"wall":"2026-07-14T23:00:00.000Z",""" + """"kind":"doc.change","data":{}}""" + "\n"),
            "$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T09:00:00.000Z")),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), null)
        assertEquals("z.slog", pick?.filename)
    }

    // -----------------------------------------------------------------------
    // A DAMAGED WALL COSTS A FILE ITS ORDER, NEVER ITS AUTHOR.
    //
    // `session.start.wall` is a plain string in the clear, so damaging a
    // classmate's timestamp costs an attacker nothing. Reading the wall and the
    // student_ref as one all-or-nothing parse meant one flipped byte threw away
    // the author along with the timestamp, demoting a partner's log to
    // `unattributed` — which an UNENROLLED recorder may select and quarantine.
    // -----------------------------------------------------------------------

    /**
     * A first line that is a perfectly readable `session.start` naming its author, whose
     * `wall` is not a parseable timestamp. The realistic shape of one flipped byte.
     */
    private fun wallDamagedHead(studentRef: String) = head("2026-13-45T99:99:99.999Z", studentRef)

    @Test
    fun `still reads ownership off a session start whose wall is unparseable`() = runBlocking {
        // Ownership is `student_ref` and ONLY `student_ref`.
        val files = mapOf("$dir/session-bob.slog" to SlogReadResult.Ok(wallDamagedHead("bob")))
        // Unenrolled: the ONLY configuration in which an `unattributed` file is
        // eligible at all, and therefore the only one the defect was reachable from.
        assertNull(selectEligible(listOf("session-bob.slog"), dir, reader(files), null))
    }

    @Test
    fun `an unparseable wall on OUR own log still leaves it quarantinable`() = runBlocking {
        // The other half of the same rule: reading ownership independently of the wall
        // also means an enrolled recorder can still act on its OWN damaged log, which
        // the all-or-nothing parse denied it.
        val files = mapOf("$dir/session-mine.slog" to SlogReadResult.Ok(wallDamagedHead("alice")))
        val pick = selectEligible(listOf("session-mine.slog"), dir, reader(files), "alice")
        assertEquals("session-mine.slog", pick?.filename)
        assertNull("unorderable, so it is the fallback and carries no text", pick?.text)
    }

    @Test
    fun `an unparseable wall is not an ordering candidate`() = runBlocking {
        val files = mapOf(
            "$dir/a.slog" to SlogReadResult.Ok(head("not-a-timestamp")),
            "$dir/z.slog" to SlogReadResult.Ok(head("2026-07-14T09:00:00.000Z")),
        )
        val pick = selectEligible(listOf("a.slog", "z.slog"), dir, reader(files), null)
        assertEquals("z.slog", pick?.filename)
    }

    @Test
    fun `an empty student_ref is treated as no student_ref`() {
        val files = mapOf("$dir/a.slog" to SlogReadResult.Ok(head("2026-07-14T09:00:00.000Z", "")))
        runBlocking {
            // Unattributed → not eligible for an enrolled recorder.
            assertNull(selectEligible(listOf("a.slog"), dir, reader(files), "alice"))
            // …and eligible for an unenrolled one.
            assertEquals("a.slog", selectEligible(listOf("a.slog"), dir, reader(files), null)?.filename)
        }
    }
}
