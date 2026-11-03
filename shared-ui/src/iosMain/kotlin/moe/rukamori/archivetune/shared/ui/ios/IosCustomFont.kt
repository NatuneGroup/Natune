@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.ui.IosHost
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The user's custom font file on this host. The document picker's security-scoped bookmark is not durable, so the
 * picked file is copied into Application Support/fonts/ and stored by its file URI, the same rule the custom
 * background keeps. One font is kept at a time: a successful pick replaces the previous file. The stored bytes are
 * checked for the sfnt container magic and parsed by Skia before they commit, so a non-font file is refused rather
 * than saved as a family that renders as the default.
 */
internal object IosCustomFont {
    private const val FONT_MAX_BYTES = 64L * 1024L * 1024L
    private val MAGIC = listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00),
        "true".toByteArray(Charsets.US_ASCII),
        "ttcf".toByteArray(Charsets.US_ASCII),
        "OTTO".toByteArray(Charsets.US_ASCII),
    )

    private fun directory(): String {
        val manager = NSFileManager.defaultManager
        val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
            ?: error("Application Support is unavailable")
        val directory = "$support/fonts"
        manager.createDirectoryAtPath(directory, true, null, null)
        return directory
    }

    private fun looksLikeFont(bytes: ByteArray): Boolean =
        bytes.size >= 16 && MAGIC.any { magic -> magic.indices.all { index -> bytes[index] == magic[index] } }

    /** The family name Skia reads from [bytes], or null when the bytes are not a drawable font. */
    private fun familyName(bytes: ByteArray): String? = runCatching {
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes))?.familyName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Lets the user pick one font file, copies it into the app container and answers its file URI and display name.
     * Null when they cancel or the picker cannot be shown; an unreadable or non-font file throws, which the settings
     * row reports like any failed pick.
     */
    suspend fun pick(host: IosHost): Pair<String, String>? {
        val handle = host.files.pickDocument(host.viewController) ?: return null
        val bytes = host.files.read(handle, FONT_MAX_BYTES)
        check(looksLikeFont(bytes)) { "The picked file is not a font" }
        val name = familyName(bytes) ?: error("The picked font could not be parsed")
        return withContext(Dispatchers.Default) {
            val manager = NSFileManager.defaultManager
            val directory = directory()
            val path = "$directory/font-${NSUUID().UUIDString}"
            val written = bytes.usePinned {
                NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile(path, true)
            }
            check(written) { "The picked font could not be stored" }
            // The pick replaces the one stored font, so earlier copies leave before the new URI is committed.
            manager.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()
                .filter { it.startsWith("font-") && "$directory/$it" != path }
                .forEach { manager.removeItemAtPath("$directory/$it", null) }
            val uri = NSURL.fileURLWithPath(path).absoluteString ?: error("The stored font has no file URI")
            uri to name
        }
    }

    /** The family the stored URI resolves to, or null when the file is gone or unreadable so the default family shows. */
    suspend fun family(uri: String): FontFamily? = withContext(Dispatchers.Default) {
        val path = NSURL.URLWithString(uri)?.takeIf { it.scheme == "file" }?.path ?: return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val bytes = if (data.length == 0uL) {
            return@withContext null
        } else {
            data.bytes?.readBytes(data.length.toInt()) ?: return@withContext null
        }
        if (!looksLikeFont(bytes)) return@withContext null
        FontFamily(Font(identity = uri, data = bytes))
    }
}
