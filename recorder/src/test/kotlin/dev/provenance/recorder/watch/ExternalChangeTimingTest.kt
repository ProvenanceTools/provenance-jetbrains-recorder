package dev.provenance.recorder.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContentRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Task 8(A) — the headless-verifiable slice of the focus/refresh timing risk. These
 * exercise real VFS refresh semantics against a LocalFileSystem temp dir. The
 * frame-activation / native-watcher / latency items that genuinely need a running
 * windowed IDE are the unchecked "VERIFY AT EXECUTION" checklist in README.md.
 */
class ExternalChangeTimingTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("timing-ws")
        emitted.clear()
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    private fun install(reg: ExpectedContentRegistry): ExternalChangeEngine {
        val engine = ExternalChangeEngine(reg)
        val saveChecker = SaveTimeExternalChangeChecker(engine, emit = { emitted.add(it) })
        val listener = VfsExternalChangeListener(
            workspaceRoot = wsRoot, engine = engine, saveChecker = saveChecker,
            emit = { emitted.add(it) }, dispatch = { it() },
        )
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, listener)
        return engine
    }

    /** 1. Out-of-order batch: write B then A, one refresh; each attributed to its own model. */
    fun testOutOfOrderBatchKeepsPerFileDirection() {
        val vfA = vfFor("a.py", "AAA\n")
        val vfB = vfFor("b.py", "BBB\n")
        val relA = relativePathOf(vfA, wsRoot)!!
        val relB = relativePathOf(vfB, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(relA, relB))
        reg.getOrCreate(relA, "AAA\n")
        reg.getOrCreate(relB, "BBB\n")
        install(reg)

        Files.writeString(wsRoot.resolve("b.py"), "b-external\n") // B first
        Files.writeString(wsRoot.resolve("a.py"), "a-external\n") // then A
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot)!!
        VfsUtil.markDirtyAndRefresh(false, false, true, rootVf)

        assertEquals(2, emitted.size)
        val byPath = emitted.associateBy { it.path }
        assertEquals(Sha256.hex("AAA\n"), byPath[relA]!!.oldHash)
        assertEquals(Sha256.hex("a-external\n"), byPath[relA]!!.newHash)
        assertEquals(Sha256.hex("BBB\n"), byPath[relB]!!.oldHash)
        assertEquals(Sha256.hex("b-external\n"), byPath[relB]!!.newHash)
    }

    /**
     * 2. Stale-timestamp non-detection: a same-length, same-mtime-preserving write is NOT
     * picked up by a timestamp-based refresh. Documents a known, accepted false-negative
     * window (VFS refresh is timestamp+length based), not a bug to chase.
     */
    fun testSameLengthSameTimestampWriteIsNotDetected() {
        val vf = vfFor("hw.py", "AAAA\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "AAAA\n")
        install(reg)

        val path = wsRoot.resolve("hw.py")
        val originalMtime: FileTime = Files.getLastModifiedTime(path)
        Files.writeString(path, "BBBB\n") // same UTF-16/byte length as "AAAA\n"
        Files.setLastModifiedTime(path, originalMtime) // pin mtime to the old value
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)

        assertEquals("timestamp+length-unchanged write is a known false-negative", 0, emitted.size)
    }

    /** 3. Editor-save vs external-write of the same file: no crash, no double emission. */
    fun testSaveThenExternalWriteSameFileProducesNoDoubleEmission() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n")
        install(reg)

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
        // Immediately overwrite on disk and refresh.
        Files.writeString(wsRoot.resolve("hw.py"), "print(1)\nprint(2)\nprint(3)\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)

        // The exact winner depends on requestor race semantics; the invariant is: no crash,
        // and no more than the two distinct real changes are each emitted at most once.
        assertTrue("at most one emission per distinct change", emitted.size <= 2)
        assertTrue("all emissions are well-formed", emitted.all { it.newHash.isNotEmpty() || it.operation == "delete" })
    }

    /**
     * 4. Async-VFS sanity: after an editor save, VirtualFile.contentsToByteArray() reflects
     * the saved content with no explicit ManagingFS.flushPendingUpdates. Guards against a
     * platform-version regression of the documented "reads through platform APIs see
     * changes immediately" guarantee that Task 4's save-time read relies on.
     */
    fun testContentsToByteArrayReflectsSaveWithoutExplicitFlush() {
        val vf = vfFor("hw.py", "print(1)\n")
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
        assertEquals("print(1)\nprint(2)\n", String(vf.contentsToByteArray(), Charsets.UTF_8))
    }
}
