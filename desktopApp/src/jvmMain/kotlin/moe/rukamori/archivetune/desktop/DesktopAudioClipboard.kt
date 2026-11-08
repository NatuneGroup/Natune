package moe.rukamori.archivetune.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/** Native file-list and Linux URI clipboard formats let users paste local audio into compatible apps/file managers. */
internal fun copyDesktopAudioFile(file: File): Boolean = try {
    if (!file.isFile) false else {
        val source = file.canonicalFile
        val uriList = DataFlavor("text/uri-list;class=java.lang.String")
        val gnomeFiles = DataFlavor("x-special/gnome-copied-files;class=java.lang.String")
        val flavors = arrayOf(DataFlavor.javaFileListFlavor, uriList, gnomeFiles)
        val transfer = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors
            override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
                DataFlavor.javaFileListFlavor -> listOf(source)
                uriList -> source.toURI().toASCIIString() + "\r\n"
                gnomeFiles -> "copy\n" + source.toURI().toASCIIString()
                else -> throw UnsupportedFlavorException(flavor)
            }
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transfer, null)
        true
    }
} catch (failure: Exception) {
    desktopDiagnostic("Audio file clipboard unavailable: ${failure.javaClass.simpleName}")
    false
}
