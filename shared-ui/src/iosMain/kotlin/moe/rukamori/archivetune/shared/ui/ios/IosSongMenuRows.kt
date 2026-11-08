@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import moe.rukamori.archivetune.shared.data.provider.YouTubeEntry
import moe.rukamori.archivetune.shared.domain.mediainfo.AudioContainer
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.ui.IosHost
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.writeToFile

/**
 * The song rows this host gained owners for: speed-dial pins, library removal, download export, cover
 * save, metadata refetch, recommendation blocks and the Telegram message link. The download row facts read the committed
 * file's container, like the desktop export does, because the stored name carries no format.
 */

/** The provider song a pin or refetch acts on, the same gate the desktop host applies to its rows. */
internal fun ContentRef.iosYouTubeSong(): Boolean =
    provider == ContentProvider.YOUTUBE.id && kind == ContentKind.SONG && !id.startsWith("telegram://")

/**
 * The idempotent desired-state edit the desktop pin row makes: unpinning drops the pin, pinning adds
 * it once, and a full speed dial refuses instead of silently dropping an existing pin.
 */
internal fun iosSongPins(pins: List<SpeedDialPin>, songId: String, pinned: Boolean): List<SpeedDialPin>? {
    val pin = SpeedDialPin(SpeedDialPinType.SONG, songId)
    if (!pinned) return pins.filterNot { it == pin }
    if (pin in pins) return pins
    if (pins.size >= 25) return null
    return pins + pin
}

/** The committed download's real container extension, sniffed like the desktop export names it. */
internal fun iosCommittedExtension(path: String): String {
    val header = iosFileHeader(path, AudioContainer.PROBE_BYTES)
    return header?.let { AudioContainer.detect(it) }?.extension ?: "bin"
}

/** Up to [maxBytes] of a stored file's head, enough for container detection. */
private fun iosFileHeader(path: String, maxBytes: Int): ByteArray? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    try {
        val data = handle.readDataUpToLength(maxBytes.toULong(), error.ptr) ?: return null
        if (data.length == 0uL) null else data.bytes?.readBytes(data.length.toInt())
    } finally {
        handle.closeAndReturnError(null)
    }
}

/**
 * The export a menu row performs: [write] stages the payload under [fileName] in a private temp
 * directory, the document picker then offers a destination and copies the staged file there, and the
 * temp directory always goes away. True once the picker delivered, null when the user cancelled.
 */
internal suspend fun iosExportStagedFile(host: IosHost, fileName: String, write: suspend (String) -> Unit): Boolean? {
    val directory = NSTemporaryDirectory() + "natune-export-" + NSUUID().UUIDString + "/"
    val manager = NSFileManager.defaultManager
    manager.createDirectoryAtPath(directory, true, null, null)
    val path = directory + fileName
    return try {
        write(path)
        host.files.exportDocuments(host.viewController, listOf(NSURL.fileURLWithPath(path)))
    } finally {
        manager.removeItemAtPath(directory, null)
    }
}

/** Copies one committed download into the staging path, leaving the stored file untouched. */
internal fun iosCopyCommitted(source: String, destination: String) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val manager = NSFileManager.defaultManager
    check(
        manager.copyItemAtURL(
            NSURL.fileURLWithPath(source),
            NSURL.fileURLWithPath(destination),
            error.ptr,
        ),
    ) { "The completed download could not be staged for export" }
}

/** Writes [bytes] to a staging path in one shot; a failed write throws rather than exporting a stub. */
internal fun iosWriteBytes(path: String, bytes: ByteArray) {
    val data = bytes.usePinned {
        NSData.create(bytes = if (bytes.isEmpty()) null else it.addressOf(0), length = bytes.size.toULong())
    }
    check(data.writeToFile(path, atomically = true)) { "The staged file could not be written" }
}

/** The decoded artwork as PNG bytes, or null when it cannot be encoded. */
internal fun iosArtworkPng(bitmap: ImageBitmap): ByteArray? =
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes?.takeIf { it.isNotEmpty() }

/** The provider entry a watch-next refresh returns, as the item the stored row is rewritten with. */
internal fun iosRefreshedItem(entry: YouTubeEntry): ContentItem =
    ContentItem(
        ref = ContentRef(ContentProvider.YOUTUBE.id, entry.kind, entry.id),
        title = entry.title,
        subtitle = entry.subtitle,
        artworkUri = entry.artworkUri,
        durationMs = entry.durationSeconds?.let { it * 1000L },
        explicit = entry.explicit,
        album = entry.album,
        artists = entry.artists,
        albumId = entry.albumId?.takeIf(String::isNotBlank),
        isVideo = entry.isVideo,
    )
