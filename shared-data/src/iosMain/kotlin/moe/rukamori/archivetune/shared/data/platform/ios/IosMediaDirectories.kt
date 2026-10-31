@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The app-container folders this host owns for media it stores itself: downloaded songs, the audio imported through
 * the document picker, and the artwork extracted from that audio's tags. They live under Application Support, the
 * app-private directory the database, the custom artwork and the queue checkpoint already use, so nothing here needs
 * a document bookmark or a user-visible folder.
 */
class IosMediaDirectories(base: String? = null) {
    private val manager = NSFileManager.defaultManager

    val root: String = base ?: requireNotNull(
        (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path,
    )

    /** Committed downloads, each `provider/source/mediaId_quality.media` beneath it. */
    val downloads: String = "$root/downloads"

    /** The audio imported through the picker; every scan walks exactly this tree. */
    val imports: String = "$root/imported-media"

    /** Artwork extracted from imported tags, named by track id. */
    val importArtwork: String = "$root/imported-media-artwork"

    fun ensure() {
        for (folder in listOf(downloads, imports, importArtwork)) {
            manager.createDirectoryAtPath(folder, true, null, null)
        }
    }
}
