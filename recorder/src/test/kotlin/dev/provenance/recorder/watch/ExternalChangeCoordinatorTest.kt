package dev.provenance.recorder.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import java.nio.file.Files
import java.nio.file.Path

/**
 * Task 7 — end-to-end integration of the coordinator with the REAL doc lifecycle: the
 * feeder seeds on open and tracks edits, so a normal editor save is clean (no false
 * positive), while a genuine external write / reload emits. Also asserts dispose() stops
 * everything.
 */
class ExternalChangeCoordinatorTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("coord-ws")
        // This test opens real editors (feeder seed path); whitelist the temp root so the
        // platform's allowed-roots guard doesn't reject it (its canonical /private form).
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot.toRealPath().toString())
        emitted.clear()
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    private fun coordinator(vararg watched: String): ExternalChangeCoordinator {
        val c = ExternalChangeCoordinator(
            project = project,
            workspaceRoot = wsRoot,
            filesUnderReview = watched.toList(),
            emit = { emitted.add(it) },
            vfsDispatch = { it() }, // synchronous for deterministic assertions
        )
        Disposer.register(testRootDisposable, c)
        return c
    }

    private fun openInEditor(vf: VirtualFile) {
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    fun testTypedThenSavedIsCleanButExternalWriteEmits() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val c = coordinator(rel)
        openInEditor(vf)
        c.start() // catch-up seeds the already-open file

        // Type through the editor — the feeder tracks the delta into the expected model.
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
        assertEquals("a normal typed+saved edit must not look external", 0, emitted.size)
        assertEquals("print(1)\nprint(2)\n", c.registry.get(rel)!!.content)

        // Now a genuine external write (CLI/git) → must emit.
        Files.writeString(wsRoot.resolve("hw.py"), "import evil\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        assertEquals(1, emitted.size)
        assertEquals("modify", emitted[0].operation)
        assertEquals(Sha256.hex("print(1)\nprint(2)\n"), emitted[0].oldHash)
        assertEquals(Sha256.hex("import evil\n"), emitted[0].newHash)
    }

    fun testSilentReloadEmitsThroughReloadPath() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val c = coordinator(rel)
        openInEditor(vf)
        c.start()

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        Files.writeString(wsRoot.resolve("hw.py"), "cli output\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(doc) }
        }

        assertTrue("reload must be detected", emitted.isNotEmpty())
        val p = emitted.last()
        assertEquals(Sha256.hex("cli output\n"), p.newHash)
        assertEquals("cli output\n", c.registry.get(rel)!!.content)
    }

    fun testDisposeStopsDetection() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val c = coordinator(rel)
        openInEditor(vf)
        c.start()
        c.registry.getOrCreate(rel, "print(1)\n")

        Disposer.dispose(c)

        Files.writeString(wsRoot.resolve("hw.py"), "after dispose\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        assertEquals("no emissions after dispose", 0, emitted.size)
    }
}
