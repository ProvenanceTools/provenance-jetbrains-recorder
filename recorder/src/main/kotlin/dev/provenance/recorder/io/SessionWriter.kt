package dev.provenance.recorder.io

import dev.provenance.core.BufferPolicyConfig
import dev.provenance.core.BufferPolicyInput
import dev.provenance.core.Clock
import dev.provenance.core.DEFAULT_BUFFER_POLICY
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.serializeEntry
import dev.provenance.core.shouldFlush
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledFuture

/**
 * Schedules the periodic time-based flush. Injectable so tests don't need a live
 * IntelliJ Application. Production passes a wrapper over
 * com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().
 */
fun interface FlushScheduler {
    fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*>
}

/**
 * Append-only byte sink for the .slog. Injectable so tests can exercise the
 * write-error path deterministically. The production impl is a FileChannel opened
 * in APPEND mode.
 */
interface ByteSink {
    fun write(bytes: ByteArray)

    fun close()
}

private class FileChannelSink(private val channel: FileChannel) : ByteSink {
    override fun write(bytes: ByteArray) {
        channel.write(ByteBuffer.wrap(bytes))
    }

    override fun close() {
        channel.close()
    }
}

/**
 * SessionWriter — owns the open sink for the .slog file (CLAUDE.md: "the session
 * writer is a class because it owns a file handle"). Buffers entries and flushes
 * on a size/time policy (PRD §4.7). Append-only; never rewrites earlier lines.
 * Mirrors session-writer.ts.
 *
 * Concurrency: unlike the TS writer's promise-chain, snapshot+write happen inside a
 * single `writeLock` monitor, so every flush is fully serialized and writes are
 * ordered (CLAUDE.md: "no unordered concurrency over operations that must be
 * ordered"). append() flushes inline only when the size threshold is hit; the
 * time-based flush runs on the injected scheduler.
 */
class SessionWriter private constructor(
    private val sink: ByteSink,
    private val clock: Clock,
    private val bufferPolicy: BufferPolicyConfig,
    private val onError: (Exception) -> Unit,
    scheduler: FlushScheduler,
) {
    private val writeLock = Object()
    private var buffer = StringBuilder()
    private var bufferedBytes = 0
    private var lastFlushAtMs = clock.now()

    @Volatile
    private var disposed = false
    private val flushFuture: ScheduledFuture<*> =
        scheduler.scheduleAtFixedRate(bufferPolicy.maxIntervalMs) {
            if (!disposed) flush()
        }

    companion object {
        fun open(
            slogPath: Path,
            clock: Clock,
            scheduler: FlushScheduler,
            bufferPolicy: BufferPolicyConfig = DEFAULT_BUFFER_POLICY,
            onError: (Exception) -> Unit = {},
        ): SessionWriter {
            slogPath.parent?.let { Files.createDirectories(it) }
            val channel = FileChannel.open(slogPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            return SessionWriter(FileChannelSink(channel), clock, bufferPolicy, onError, scheduler)
        }

        /** Test seam: inject a ByteSink directly (e.g. a failing sink for the onError path). */
        internal fun openWithSink(
            sink: ByteSink,
            clock: Clock,
            scheduler: FlushScheduler,
            bufferPolicy: BufferPolicyConfig = DEFAULT_BUFFER_POLICY,
            onError: (Exception) -> Unit = {},
        ): SessionWriter = SessionWriter(sink, clock, bufferPolicy, onError, scheduler)
    }

    /**
     * Synchronously enqueue an entry. Flushes inline if the size policy says so.
     * Throws if called after dispose().
     */
    fun append(entry: HashedEnvelope) {
        check(!disposed) { "SessionWriter.append() called after dispose()" }
        val line = serializeEntry(entry)
        val doInlineFlush: Boolean
        synchronized(writeLock) {
            buffer.append(line)
            bufferedBytes += line.toByteArray(StandardCharsets.UTF_8).size
            doInlineFlush = shouldFlush(BufferPolicyInput(bufferedBytes, lastFlushAtMs, clock.now()), bufferPolicy)
        }
        if (doInlineFlush) flush()
    }

    /** Force-flush whatever is buffered. Serialized via writeLock; ordered. */
    fun flush() {
        synchronized(writeLock) {
            if (buffer.isEmpty()) return
            val snapshot = buffer.toString()
            // Drop the buffered lines up front; on write failure they are NOT restored
            // (a partial write could otherwise duplicate lines). Mirrors the TS writer.
            buffer = StringBuilder()
            bufferedBytes = 0
            try {
                sink.write(snapshot.toByteArray(StandardCharsets.UTF_8))
                lastFlushAtMs = clock.now()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /**
     * Cancel the periodic flush, do a final flush, close the sink. Idempotent.
     * A subsequent append() throws.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        flushFuture.cancel(false)
        flush()
        try {
            sink.close()
        } catch (e: Exception) {
            onError(e)
        }
    }
}
