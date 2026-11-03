package moe.rukamori.archivetune.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherEnvelope

/**
 * The custom Listen Together member picture, kept as one JPEG beside the instance state. The pick decodes whatever
 * the platform reads, downscales to the wire edge and re-encodes at falling qualities until the protocol bound holds,
 * as the iOS picker does; nothing larger than [ListenTogetherEnvelope.MAX_AVATAR_BYTES] is stored.
 */
internal class DesktopListenTogetherPicture(private val directory: Path) {
    private val file = directory.resolve(FILE_NAME)

    /** The stored picture, or null when none was saved or it no longer fits the wire bound. */
    suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Files.readAllBytes(file).takeIf { it.isNotEmpty() && it.size <= ListenTogetherEnvelope.MAX_AVATAR_BYTES }
        } catch (_: Exception) {
            null
        }
    }

    /** The file pick plus the bounded JPEG write. False means the user cancelled or the image was unusable. */
    suspend fun pickAndStore(): Boolean {
        val picked = pickFile() ?: return false
        val bytes = withContext(Dispatchers.IO) { encode(picked) } ?: return false
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            Files.write(file, bytes)
        }
        return true
    }

    /** The OS pick on the event queue, as the artwork chooser opens its own. Null when the user cancels. */
    private suspend fun pickFile(): File? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val chosen = try {
                val dialog = FileDialog(null as Frame?, DesktopStrings["listen_together_avatar_pick"], FileDialog.LOAD)
                dialog.isMultipleMode = false
                dialog.setFilenameFilter { _, name ->
                    name.substringAfterLast('.', "").lowercase() in PICTURE_EXTENSIONS
                }
                // A modal file dialog blocks this event-queue task until it closes, while AWT keeps pumping events.
                dialog.isVisible = true
                val name = dialog.file
                val folder = dialog.directory
                dialog.dispose()
                if (name == null || folder == null) null else File(folder, name)
            } catch (failure: Exception) {
                desktopDiagnostic("avatar chooser unavailable: ${failure.javaClass.simpleName}")
                null
            }
            if (continuation.isActive) continuation.resume(chosen)
        }
    }

    /** Downscales to the wire edge and re-encodes at falling JPEG qualities until the byte bound holds. */
    private fun encode(source: File): ByteArray? {
        val decoded = try {
            ImageIO.read(source)
        } catch (_: Exception) {
            null
        } ?: return null
        val scale = MAX_EDGE.toDouble() / maxOf(decoded.width, decoded.height).coerceAtLeast(1)
        val width = if (scale >= 1.0) decoded.width else (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = if (scale >= 1.0) decoded.height else (decoded.height * scale).toInt().coerceAtLeast(1)
        // JPEG keeps no alpha; a transparent corner is flattened on white rather than black.
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(decoded, AffineTransform.getScaleInstance(
                width.toDouble() / decoded.width, height.toDouble() / decoded.height), null)
        } finally {
            graphics.dispose()
        }
        for (quality in JPEG_QUALITIES) {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next() ?: return null
            val out = ByteArrayOutputStream()
            try {
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.output = ImageIO.createImageOutputStream(out)
                writer.write(null, IIOImage(canvas, null, null), param)
            } finally {
                writer.dispose()
            }
            val bytes = out.toByteArray()
            if (bytes.size <= ListenTogetherEnvelope.MAX_AVATAR_BYTES) return bytes
        }
        return null
    }

    private companion object {
        const val FILE_NAME = "listen-together-avatar.jpg"
        const val MAX_EDGE = 192
        val JPEG_QUALITIES = floatArrayOf(0.7f, 0.55f, 0.4f)
        val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}
