package dev.provenance.recorder.watch

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContentRegistry
import java.nio.file.Files
import java.nio.file.Path

/** Task 4 — save-time hash check against a real LocalFileSystem file. */
class SaveTimeExternalChangeCheckerTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("save-check-ws")
        emitted.clear()
    }

    private fun seedFile(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    fun testCleanSaveDoesNotEmit() {
        val vf = seedFile("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n") // expected == disk
        SaveTimeExternalChangeChecker(ExternalChangeEngine(reg), emit = { emitted.add(it) })
            .checkAfterSave(rel, vf)
        assertEquals(0, emitted.size)
    }

    fun testExternalOverwriteBeforeSaveEmitsWithCorrectDirection() {
        val vf = seedFile("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, "print(1)\n") // what the editor believed
        // Something else wrote between our last observed change and the save:
        Files.writeString(wsRoot.resolve("hw.py"), "import os\nos.system('rm -rf /')\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)

        SaveTimeExternalChangeChecker(ExternalChangeEngine(reg), emit = { emitted.add(it) })
            .checkAfterSave(rel, vf)

        assertEquals(1, emitted.size)
        val p = emitted[0]
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("print(1)\n"), p.oldHash) // pre-overwrite expected
        assertEquals(Sha256.hex("import os\nos.system('rm -rf /')\n"), p.newHash) // disk
    }

    fun testFileNeverOpenedIsNoOp() {
        val vf = seedFile("hw.py", "print(1)\n")
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel)) // watched but no registry entry
        SaveTimeExternalChangeChecker(ExternalChangeEngine(reg), emit = { emitted.add(it) })
            .checkAfterSave(rel, vf)
        assertEquals(0, emitted.size)
    }
}
