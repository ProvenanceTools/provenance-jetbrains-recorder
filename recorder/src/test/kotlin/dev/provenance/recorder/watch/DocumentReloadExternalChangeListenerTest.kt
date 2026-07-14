package dev.provenance.recorder.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContentRegistry
import java.nio.file.Files
import java.nio.file.Path

/** Task 6 — reload-from-disk detection via FileDocumentManagerListener.fileContentReloaded. */
class DocumentReloadExternalChangeListenerTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("reload-ws")
        emitted.clear()
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    private fun install(reg: ExpectedContentRegistry): ExternalChangeEngine {
        val engine = ExternalChangeEngine(reg)
        val listener = DocumentReloadExternalChangeListener(wsRoot, engine, emit = { emitted.add(it) })
        project.messageBus.connect(testRootDisposable)
            .subscribe(FileDocumentManagerListener.TOPIC, listener)
        return engine
    }

    private fun reloadFromDisk(vf: VirtualFile, newContent: String, doc: com.intellij.openapi.editor.Document) {
        Files.writeString(wsRoot.resolve(vf.name), newContent)
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(doc) }
        }
    }

    fun testSilentReloadWithDivergedContentEmitsAndResets() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n")
        val engine = install(reg)

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        reloadFromDisk(vf, "reloaded from cli\n", doc)

        assertEquals(1, emitted.size)
        val p = emitted[0]
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("print(1)\n"), p.oldHash)
        assertEquals(Sha256.hex("reloaded from cli\n"), p.newHash)
        assertEquals("reloaded from cli\n", engine.registry.get(rel)!!.content) // reset
    }

    fun testReloadMatchingExpectedIsSilent() {
        val vf = vfFor("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n")
        install(reg)

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        // Rewrite identical content then reload — a no-op external touch.
        reloadFromDisk(vf, "print(1)\n", doc)
        assertEquals(0, emitted.size)
    }

    fun testUnwatchedFileIsIgnored() {
        val vf = vfFor("scratch.py", "print(1)\n")
        val reg = ExpectedContentRegistry(listOf("other.py")) // scratch.py not watched
        install(reg)

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        reloadFromDisk(vf, "changed\n", doc)
        assertEquals(0, emitted.size)
    }
}
