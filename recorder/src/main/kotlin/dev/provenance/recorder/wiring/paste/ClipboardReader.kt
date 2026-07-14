package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

/** Thin seam over CopyPasteManager so PasteInterceptHandler is testable with a fake. */
interface ClipboardReader {
    /** Current clipboard text, or null if the clipboard is empty or holds non-text data. */
    fun readText(): String?
}

/**
 * Reads the clipboard the same way IntelliJ's own PasteHandler does when no custom
 * Producer is supplied (CopyPasteManager.getInstance().getContents()). A clipboard
 * holding non-text data (e.g. an image) yields null, not an error.
 */
class CopyPasteManagerClipboardReader : ClipboardReader {
    override fun readText(): String? {
        val transferable = CopyPasteManager.getInstance().contents ?: return null
        return try {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (e: UnsupportedFlavorException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
