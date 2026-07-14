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

/**
 * Task 5 — VFS BulkFileListener against a REAL LocalFileSystem temp dir (not the
 * in-memory TempFileSystem), so markDirtyAndRefresh drives the actual VFS refresh
 * code path with meaningful isFromSave()/isFromRefresh() flags.
 */
class VfsExternalChangeListenerTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("vfs-listener-ws")
        emitted.clear()
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    private fun install(
        reg: ExpectedContentRegistry,
        isRecentEditorChange: (String) -> Boolean = { false },
    ): ExternalChangeEngine {
        val engine = ExternalChangeEngine(reg)
        val saveChecker = SaveTimeExternalChangeChecker(engine, emit = { emitted.add(it) })
        val listener = VfsExternalChangeListener(
            workspaceRoot = wsRoot,
            engine = engine,
            saveChecker = saveChecker,
            emit = { emitted.add(it) },
            isRecentEditorChange = isRecentEditorChange,
            dispatch = { it() }, // synchronous for deterministic assertions
        )
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, listener)
        return engine
    }

    private fun overwriteOnDisk(name: String, content: String, vf: VirtualFile) {
        Files.writeString(wsRoot.resolve(name), content)
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
    }

    fun testExternalWriteEmitsModifyWithCorrectDirection() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n")
        install(reg)

        overwriteOnDisk("hw.py", "import evil\n", vf)

        assertEquals(1, emitted.size)
        val p = emitted[0]
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("print(1)\n"), p.oldHash) // expected model
        assertEquals(Sha256.hex("import evil\n"), p.newHash) // on-disk reality
    }

    fun testEditorSaveIsRoutedToSaveCheckerNotExternalPath() {
        // Discriminator: with the external recency-guard forced ON, only the save-checker
        // branch can emit. An editor save (isFromSave) whose model diverged must still
        // emit — proving isFromSave routes to the save-time check, not the external path.
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n") // model NOT updated with the edit below
        install(reg, isRecentEditorChange = { true }) // suppress the external branch entirely

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }

        assertEquals("save-checker must emit despite recency guard", 1, emitted.size)
        assertEquals("modify", emitted[0].operation)
        assertEquals(Sha256.hex("print(1)\n"), emitted[0].oldHash)
        assertEquals(Sha256.hex("print(1)\nprint(2)\n"), emitted[0].newHash)
    }

    fun testExternalWriteSuppressedByRecencyGuard() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n")
        install(reg, isRecentEditorChange = { true }) // external branch suppressed

        overwriteOnDisk("hw.py", "changed\n", vf)
        assertEquals("external write within recency window is a no-op", 0, emitted.size)
    }

    fun testCreateEmitsOperationCreate() {
        // Seed a watched path that doesn't exist yet, install, then create it on disk.
        val rel = "created.py"
        val reg = ExpectedContentRegistry(listOf(rel))
        install(reg)
        // Load the parent dir into the VFS snapshot so create events fire for children.
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot)!!
        rootVf.children // force-load
        Files.writeString(wsRoot.resolve(rel), "brand new\n")
        VfsUtil.markDirtyAndRefresh(false, false, true, rootVf)

        assertEquals(1, emitted.size)
        assertEquals("create", emitted[0].operation)
        assertEquals("", emitted[0].oldHash)
        assertEquals(Sha256.hex("brand new\n"), emitted[0].newHash)
    }

    fun testDeleteEmitsOperationDeleteAndDropsEntry() {
        val vf = vfFor("gone.py", "temporary\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "temporary\n")
        val engine = install(reg)

        WriteAction.runAndWait<java.io.IOException> { vf.delete(this) }

        assertEquals(1, emitted.size)
        assertEquals("delete", emitted[0].operation)
        assertEquals(Sha256.hex("temporary\n"), emitted[0].oldHash)
        assertEquals("", emitted[0].newHash)
        assertNull(engine.registry.get(rel))
    }

    fun testBatchRefreshEmitsForBothFilesIndependently() {
        val vfA = vfFor("a.py", "AAA\n")
        val vfB = vfFor("b.py", "BBB\n")
        val relA = relativePathOf(vfA, wsRoot)!!
        val relB = relativePathOf(vfB, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(relA, relB))
        reg.getOrCreate(relA, "AAA\n")
        reg.getOrCreate(relB, "BBB\n")
        install(reg)

        Files.writeString(wsRoot.resolve("a.py"), "aaa-changed\n")
        Files.writeString(wsRoot.resolve("b.py"), "bbb-changed\n")
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot)!!
        VfsUtil.markDirtyAndRefresh(false, false, true, rootVf)

        assertEquals(2, emitted.size)
        val byPath = emitted.associateBy { it.path }
        assertEquals(Sha256.hex("aaa-changed\n"), byPath[relA]!!.newHash)
        assertEquals(Sha256.hex("bbb-changed\n"), byPath[relB]!!.newHash)
    }
}
