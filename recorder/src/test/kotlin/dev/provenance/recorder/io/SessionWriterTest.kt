package dev.provenance.recorder.io

import dev.provenance.core.BufferPolicyConfig
import dev.provenance.core.Envelope
import dev.provenance.core.FixedClock
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.chainEntry
import dev.provenance.core.parseEntries
import dev.provenance.core.serializeEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.ScheduledFuture

/** A FlushScheduler that captures the periodic task instead of running it. */
private class ManualScheduler : FlushScheduler {
    var task: Runnable? = null
    var canceled = false

    override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> {
        this.task = task
        return FakeFuture { canceled = true }
    }

    fun tick() = task?.run()
}

private class FakeFuture(private val onCancel: () -> Unit) : ScheduledFuture<Any?> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean { onCancel(); return true }
    override fun isCancelled() = false
    override fun isDone() = false
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): Any? = null
    override fun getDelay(unit: java.util.concurrent.TimeUnit): Long = 0
    override fun compareTo(other: java.util.concurrent.Delayed?): Int = 0
}

private class FailingSink : ByteSink {
    var closed = false
    override fun write(bytes: ByteArray): Unit = throw java.io.IOException("disk full")
    override fun close() { closed = true }
}

class SessionWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var seq = 0L
    private var prev = GENESIS_PREV_HASH

    private fun entry(kind: String): HashedEnvelope {
        val e = chainEntry(prev, Envelope(seq, seq, "2026-07-14T00:00:00Z", kind, buildJsonObject { put("k", kind) }))
        seq += 1; prev = e.hash
        return e
    }

    @Test
    fun `append then flush writes serialized lines in order`() {
        val slog = tmp.root.toPath().resolve("s.slog")
        val w = SessionWriter.open(slog, FixedClock(0), ManualScheduler())
        val e0 = entry("session.start")
        val e1 = entry("doc.open")
        w.append(e0)
        w.append(e1)
        w.flush()
        val text = String(Files.readAllBytes(slog), Charsets.UTF_8)
        assertEquals(serializeEntry(e0) + serializeEntry(e1), text)
        val parsed = parseEntries(text)
        assertTrue(parsed is dev.provenance.core.ParseResult.Ok)
        assertEquals(2, (parsed as dev.provenance.core.ParseResult.Ok).entries.size)
        w.dispose()
    }

    @Test
    fun `size threshold triggers automatic flush without explicit flush`() {
        val slog = tmp.root.toPath().resolve("s.slog")
        // Tiny maxBytes so a single entry exceeds it and append flushes inline.
        val w = SessionWriter.open(slog, FixedClock(0), ManualScheduler(), BufferPolicyConfig(maxBytes = 1, maxIntervalMs = 100_000))
        val e0 = entry("session.start")
        w.append(e0)
        val text = String(Files.readAllBytes(slog), Charsets.UTF_8)
        assertEquals(serializeEntry(e0), text)
        w.dispose()
    }

    @Test
    fun `periodic scheduler tick flushes buffered bytes`() {
        val slog = tmp.root.toPath().resolve("s.slog")
        val sched = ManualScheduler()
        val w = SessionWriter.open(slog, FixedClock(0), sched, BufferPolicyConfig(maxBytes = 1_000_000, maxIntervalMs = 1000))
        w.append(entry("session.start"))
        assertEquals(0, Files.readAllBytes(slog).size) // not yet flushed
        sched.tick()
        assertTrue(Files.readAllBytes(slog).isNotEmpty())
        w.dispose()
    }

    @Test
    fun `dispose flushes remaining bytes and append afterwards throws`() {
        val slog = tmp.root.toPath().resolve("s.slog")
        val w = SessionWriter.open(slog, FixedClock(0), ManualScheduler(), BufferPolicyConfig(maxBytes = 1_000_000, maxIntervalMs = 100_000))
        val e0 = entry("session.start")
        w.append(e0)
        w.dispose()
        assertEquals(serializeEntry(e0), String(Files.readAllBytes(slog), Charsets.UTF_8))
        var threw = false
        try { w.append(entry("doc.open")) } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `write error invokes onError and drops the line`() {
        val errors = mutableListOf<Exception>()
        val sink = FailingSink()
        val w = SessionWriter.openWithSink(sink, FixedClock(0), ManualScheduler(), onError = { errors.add(it) })
        w.flush() // empty — no error
        assertTrue(errors.isEmpty())
        w.append(entry("session.start"))
        w.flush()
        assertEquals(1, errors.size)
        assertTrue(errors[0] is java.io.IOException)
        // A second flush has nothing buffered (line was dropped) → no further error.
        w.flush()
        assertEquals(1, errors.size)
    }
}
