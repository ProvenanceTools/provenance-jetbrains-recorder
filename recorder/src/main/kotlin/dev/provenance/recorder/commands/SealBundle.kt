package dev.provenance.recorder.commands

import dev.provenance.core.BundleManifest
import dev.provenance.core.ChainCheck
import dev.provenance.core.ParseResult
import dev.provenance.core.SessionEntry
import dev.provenance.core.Sha256
import dev.provenance.core.SubmissionFileEntry
import dev.provenance.core.parseEntries
import dev.provenance.core.signBundleManifest
import dev.provenance.core.validateChain
import dev.provenance.recorder.io.atomicWriteFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundle seal (PRD §4.6 seal, §5.3 bundle = ZIP of .provenance/ + submission files).
 * Direct port of seal.ts, including the "never abort on a broken/unparseable chain,
 * accumulate warnings instead" policy. Uses the JDK's java.util.zip (no jszip).
 * Never modifies manifest.json / manifest.sig after writing — they are signed.
 */
sealed interface SealResult {
    data class Ok(
        val bundlePath: Path,
        val manifestSha256: String,
        val chainBroken: Boolean,
        val unreadableSession: Boolean,
    ) : SealResult

    data object NoSessions : SealResult

    data class WriteError(val message: String) : SealResult
}

private val SHA256_EMPTY = Sha256.hex(ByteArray(0))

private fun sha256OfFile(path: Path): String =
    if (Files.exists(path)) Sha256.hex(Files.readAllBytes(path)) else SHA256_EMPTY

/** ISO timestamp with colons replaced by dashes, for use in filenames. */
private fun filenameTimestamp(instant: Instant): String = instant.toString().replace(":", "-")

fun sealBundle(
    provenanceDir: Path,
    workspaceRoot: Path,
    assignmentId: String,
    semester: String,
    filesUnderReview: List<String>,
    sessionPrivkey: ByteArray,
    computeExtensionHash: () -> String,
    outputDir: Path = workspaceRoot,
    now: () -> Instant = Instant::now,
): SealResult {
    // Step 1: list .slog files (excluding .slog.meta).
    if (!Files.isDirectory(provenanceDir)) return SealResult.NoSessions
    val slogFiles = Files.list(provenanceDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".slog") && !it.fileName.toString().endsWith(".slog.meta") }
            .map { it.fileName.toString() }
            .sorted()
            .toList()
    }
    if (slogFiles.isEmpty()) return SealResult.NoSessions

    // Step 2: parse + validate each .slog. Warnings accumulate; never abort.
    var chainBroken = false
    var unreadableSession = false
    val sessions = ArrayList<SessionEntry>(slogFiles.size)

    for (filename in slogFiles) {
        val slogPath = provenanceDir.resolve(filename)
        val metaPath = provenanceDir.resolve("$filename.meta")
        val slogText = try {
            String(Files.readAllBytes(slogPath), Charsets.UTF_8)
        } catch (e: Exception) {
            return SealResult.WriteError("Failed to read $filename: ${e.message}")
        }

        val slogSha = sha256OfFile(slogPath)
        val metaSha = sha256OfFile(metaPath)

        when (val parsed = parseEntries(slogText)) {
            is ParseResult.Err -> {
                unreadableSession = true
                sessions.add(SessionEntry(null, null, slogSha, metaSha))
            }
            is ParseResult.Ok -> {
                if (validateChain(parsed.entries) != ChainCheck.Valid) chainBroken = true
                val first = parsed.entries.firstOrNull()
                var sessionId: String? = null
                var prevSessionId: String? = null
                if (first != null && first.kind == "session.start") {
                    sessionId = strOrNull(first.data["session_id"])
                    prevSessionId = strOrNull(first.data["prev_session_id"])
                }
                if (sessionId == null) unreadableSession = true
                sessions.add(SessionEntry(sessionId, prevSessionId, slogSha, metaSha))
            }
        }
    }

    // Step 3: read reviewed files from disk.
    data class Reviewed(val path: String, val present: Boolean, val sha256: String?, val bytes: ByteArray?)
    val reviewed = filesUnderReview.map { rel ->
        val abs = workspaceRoot.resolve(rel)
        try {
            val bytes = Files.readAllBytes(abs)
            Reviewed(rel, true, Sha256.hex(bytes), bytes)
        } catch (_: Exception) {
            Reviewed(rel, false, null, null)
        }
    }
    val submissionFiles = reviewed.map {
        if (it.present) SubmissionFileEntry(it.path, "present", it.sha256) else SubmissionFileEntry(it.path, "missing", null)
    }

    // Step 4: build the 1.1 manifest.
    val extensionHash = try {
        computeExtensionHash()
    } catch (e: Exception) {
        return SealResult.WriteError("Failed to compute extension hash: ${e.message}")
    }
    val manifest = BundleManifest(
        formatVersion = "1.1",
        assignmentId = assignmentId,
        semester = semester,
        extensionHash = extensionHash,
        sessions = sessions,
        submissionFiles = submissionFiles,
    )

    // Step 5: sign + atomic-write manifest.json (the exact signed bytes) and manifest.sig.
    val signed = try {
        signBundleManifest(manifest, sessionPrivkey)
    } catch (e: Exception) {
        return SealResult.WriteError("Failed to sign manifest: ${e.message}")
    }
    val manifestPath = provenanceDir.resolve("manifest.json")
    val sigPath = provenanceDir.resolve("manifest.sig")
    try {
        atomicWriteFile(manifestPath, signed.canonicalJson)
        atomicWriteFile(sigPath, signed.signatureHex)
    } catch (e: Exception) {
        return SealResult.WriteError("Failed to write manifest/sig: ${e.message}")
    }
    val manifestSha256 = Sha256.hex(signed.canonicalJson.toByteArray(Charsets.UTF_8))

    // Step 6: zip everything in provenanceDir (skip .tmp / .corrupt-*) + present reviewed files.
    val ts = filenameTimestamp(now())
    val bundlePath = outputDir.resolve("$assignmentId-bundle-$ts.zip")
    try {
        Files.createDirectories(outputDir)
        ZipOutputStream(Files.newOutputStream(bundlePath)).use { zip ->
            val dirFiles = Files.list(provenanceDir).use { s ->
                s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList()
            }
            for (name in dirFiles) {
                if (name.endsWith(".tmp") || name.contains(".corrupt-")) continue
                val bytes = try {
                    Files.readAllBytes(provenanceDir.resolve(name))
                } catch (_: Exception) {
                    continue // disappeared between listing and read — skip
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for (r in reviewed) {
                if (r.present && r.bytes != null) {
                    zip.putNextEntry(ZipEntry(r.path))
                    zip.write(r.bytes)
                    zip.closeEntry()
                }
            }
        }
    } catch (e: Exception) {
        return SealResult.WriteError("Failed to write bundle ZIP: ${e.message}")
    }

    return SealResult.Ok(bundlePath, manifestSha256, chainBroken, unreadableSession)
}

private fun strOrNull(elem: kotlinx.serialization.json.JsonElement?): String? {
    val prim = elem as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (!prim.isString) return null
    return prim.content
}
