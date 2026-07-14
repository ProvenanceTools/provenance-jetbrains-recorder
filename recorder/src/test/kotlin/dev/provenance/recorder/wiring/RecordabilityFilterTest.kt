package dev.provenance.recorder.wiring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class RecordabilityFilterTest {
    private val ws = Paths.get("/work/hw03")
    private val prov = Paths.get("/work/hw03/.provenance")

    @Test
    fun `records a normal workspace file`() {
        assertTrue(isRecordablePath(Paths.get("/work/hw03/hw.py"), true, ws, prov))
        assertTrue(isRecordablePath(Paths.get("/work/hw03/src/main.py"), true, ws, prov))
    }

    @Test
    fun `rejects non-local file system`() {
        assertFalse(isRecordablePath(Paths.get("/work/hw03/hw.py"), false, ws, prov))
    }

    @Test
    fun `rejects null nio path`() {
        assertFalse(isRecordablePath(null, true, ws, prov))
    }

    @Test
    fun `rejects file outside workspace`() {
        assertFalse(isRecordablePath(Paths.get("/other/hw.py"), true, ws, prov))
    }

    @Test
    fun `rejects files inside the provenance dir`() {
        assertFalse(isRecordablePath(Paths.get("/work/hw03/.provenance/session-1.slog"), true, ws, prov))
        assertFalse(isRecordablePath(Paths.get("/work/hw03/.provenance/manifest.json"), true, ws, prov))
    }

    @Test
    fun `rejects the activation manifest files`() {
        assertFalse(isRecordablePath(Paths.get("/work/hw03/.provenance-manifest"), true, ws, prov))
        assertFalse(isRecordablePath(Paths.get("/work/hw03/provenance-manifest"), true, ws, prov))
    }
}
