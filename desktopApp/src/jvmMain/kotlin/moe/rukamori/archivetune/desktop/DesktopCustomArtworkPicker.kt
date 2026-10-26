package moe.rukamori.archivetune.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.ui.screens.customartwork.CustomArtworkKind

private val COVER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")
private val CANVAS_EXTENSIONS = COVER_EXTENSIONS + setOf("mp4", "webm", "mkv", "mov")

/**
 * The system file chooser for one custom artwork pick. It suspends until the user picks a file or cancels. The name
 * filter only guides the chooser, and some platforms ignore it; the repository's decodability check decides.
 */
internal suspend fun pickDesktopArtworkFile(kind: CustomArtworkKind): CustomArtworkSource? {
    val file = suspendCancellableCoroutine<File?> { continuation ->
        EventQueue.invokeLater {
            val chosen = try {
                val dialog = FileDialog(null as Frame?, DesktopStrings["custom_artwork_pick"], FileDialog.LOAD)
                val accepted = if (kind == CustomArtworkKind.COVER) COVER_EXTENSIONS else CANVAS_EXTENSIONS
                dialog.isMultipleMode = false
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in accepted }
                // A modal file dialog blocks this event-queue task until it closes, while AWT keeps pumping events.
                dialog.isVisible = true
                val name = dialog.file
                val folder = dialog.directory
                dialog.dispose()
                if (name == null || folder == null) null else File(folder, name)
            } catch (failure: Exception) {
                desktopDiagnostic("file chooser unavailable: ${failure.javaClass.simpleName}")
                null
            }
            if (continuation.isActive) continuation.resume(chosen)
        }
    } ?: return null
    return withContext(Dispatchers.IO) { DesktopFileArtworkSource(file.toPath()) }
}

/** A picked file. The type probe touches the disk, so the source is built on the IO dispatcher. */
private class DesktopFileArtworkSource(private val path: Path) : CustomArtworkSource {
    override val mimeType: String? = try {
        Files.probeContentType(path)
    } catch (_: IOException) {
        null
    }

    override val fileExtension: String? = path.fileName?.toString()?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    override suspend fun open(): CustomArtworkReader? = withContext(Dispatchers.IO) {
        try {
            if (!Files.isRegularFile(path)) null else StreamArtworkReader(Files.newInputStream(path))
        } catch (_: IOException) {
            null
        }
    }
}

private class StreamArtworkReader(private val stream: InputStream) : CustomArtworkReader {
    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { stream.read(buffer) }

    override suspend fun close() {
        withContext(Dispatchers.IO) { stream.close() }
    }
}
