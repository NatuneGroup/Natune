/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFile
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkWriter
import platform.Foundation.*
import platform.UIKit.UIImage
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove

/**
 * Custom covers and canvases under the app's Application Support directory, the iOS counterpart of the Android app files
 * directory that the Android store and the desktop host use. Each [CustomArtworkDirectory] is one folder beneath it, and
 * every file name stays inside its folder. A folder that is a symbolic link is refused rather than followed, as the
 * desktop host refuses it.
 *
 * The decodability check asks UIKit for the image, which reads the header and writes no cache file. A format the running
 * system cannot decode is refused as unsupported, never stored and then shown broken. Application Support is app-private,
 * so no document picker or bookmark is involved.
 *
 * Blocking file calls run on [Dispatchers.Default], never on the main thread. Nothing here is compiled or run on an Apple
 * host yet. The verification is deferred by the user.
 */
class IosCustomArtworkFiles : CustomArtworkFiles {
    private val manager = NSFileManager.defaultManager
    private val root: String = requireNotNull(
        (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path,
    )

    override suspend fun list(directory: CustomArtworkDirectory): List<CustomArtworkFile> =
        withContext(Dispatchers.Default) {
            val folder = folderOrNull(directory) ?: return@withContext emptyList<CustomArtworkFile>()
            val names = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                manager.contentsOfDirectoryAtPath(folder, error.ptr)
            } ?: return@withContext emptyList<CustomArtworkFile>()
            names.filterIsInstance<String>().mapNotNull { name ->
                val attributes = attributesOf("$folder/$name") ?: return@mapNotNull null
                // A symbolic link or a directory is not an override, so only regular files are listed.
                if (attributes[NSFileType] != NSFileTypeRegular) return@mapNotNull null
                CustomArtworkFile(name, (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L)
            }
        }

    override suspend fun openWriter(directory: CustomArtworkDirectory, name: String): CustomArtworkWriter? =
        withContext(Dispatchers.Default) {
            val folder = ensureFolder(directory) ?: return@withContext null
            // "wb" truncates an existing file of that name, as the contract requires.
            val output = fopen(fileOf(folder, name), "wb") ?: return@withContext null
            IosArtworkWriter(output)
        }

    override suspend fun rename(directory: CustomArtworkDirectory, from: String, to: String): Boolean =
        withContext(Dispatchers.Default) {
            val folder = folderOrNull(directory) ?: return@withContext false
            // Both names sit in one folder, so this POSIX rename is atomic and replaces the target.
            platform.posix.rename(fileOf(folder, from), fileOf(folder, to)) == 0
        }

    override suspend fun delete(directory: CustomArtworkDirectory, name: String) {
        withContext(Dispatchers.Default) {
            // Best effort by contract: a file that survives is removed again by the next scan.
            folderOrNull(directory)?.let { folder -> remove(fileOf(folder, name)) }
        }
    }

    override suspend fun isDecodableImage(directory: CustomArtworkDirectory, name: String): Boolean =
        withContext(Dispatchers.Default) {
            val folder = folderOrNull(directory) ?: return@withContext false
            val image = UIImage.imageWithContentsOfFile(fileOf(folder, name)) ?: return@withContext false
            image.size.useContents { width > 0.0 && height > 0.0 }
        }

    override fun uriOf(directory: CustomArtworkDirectory, name: String): String {
        val folder = checkNotNull(folderOrNull(directory)) { "Custom artwork folder is a symbolic link" }
        return checkNotNull(NSURL.fileURLWithPath(fileOf(folder, name)).absoluteString)
    }

    /** The folder of [directory], or null when that folder is a symbolic link, which is refused rather than followed. */
    private fun folderOrNull(directory: CustomArtworkDirectory): String? {
        val folder = "$root/${directory.directoryName}"
        return if (attributesOf(folder)?.get(NSFileType) == NSFileTypeSymbolicLink) null else folder
    }

    /** Creates the folder of [directory] when it is missing. Null when it cannot be created or is a symbolic link. */
    private fun ensureFolder(directory: CustomArtworkDirectory): String? {
        val folder = folderOrNull(directory) ?: return null
        val created = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            manager.createDirectoryAtPath(folder, true, null, error.ptr)
        }
        return folder.takeIf { created }
    }

    /** A name must be one plain file name, so it can never leave its folder. */
    private fun fileOf(folder: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Custom artwork name must be a plain file name"
        }
        return "$folder/$name"
    }

    /** The attributes of [path] without following a final symbolic link, or null when nothing exists there. */
    private fun attributesOf(path: String): Map<Any?, *>? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        manager.attributesOfItemAtPath(path, error.ptr)
    }

    /** Sequential writes into one open file. The repository closes every writer, including after a failed copy. */
    private class IosArtworkWriter(private val output: CPointer<FILE>) : CustomArtworkWriter {
        override suspend fun write(buffer: ByteArray, length: Int) {
            require(length in 0..buffer.size)
            if (length == 0) return
            withContext(Dispatchers.Default) {
                val written = buffer.usePinned { fwrite(it.addressOf(0), 1uL, length.toULong(), output) }
                check(written == length.toULong()) { "Custom artwork write failed" }
            }
        }

        override suspend fun close() {
            withContext(Dispatchers.Default) {
                check(fclose(output) == 0) { "Custom artwork close failed" }
            }
        }
    }
}
