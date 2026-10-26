/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.customartwork

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFile
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkWriter

/**
 * Desktop custom-artwork files under [root], the app's data directory. Each [CustomArtworkDirectory] is one folder
 * beneath it, and every file name is confined to its folder. A folder that is a symbolic link is refused rather than
 * followed, so a link planted in the data directory cannot redirect an override somewhere else.
 *
 * The decodability probe reads only the image header, through a file-backed ImageIO stream, so it never writes a cache
 * file. It accepts the formats the JDK decodes (PNG, JPEG, GIF and BMP) and refuses the rest as unsupported. WebP, HEIC
 * and AVIF covers therefore need a decoder that the desktop host does not bundle yet.
 */
class JvmCustomArtworkFiles(root: Path) : CustomArtworkFiles {
    private val root: Path = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath()

    override suspend fun list(directory: CustomArtworkDirectory): List<CustomArtworkFile> =
        withContext(Dispatchers.IO) {
            val folder =
                try {
                    folderOf(directory)
                } catch (error: IOException) {
                    return@withContext emptyList<CustomArtworkFile>()
                }
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return@withContext emptyList<CustomArtworkFile>()
            try {
                Files.newDirectoryStream(folder).use { entries ->
                    entries
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .map { CustomArtworkFile(it.fileName.toString(), Files.size(it)) }
                }
            } catch (error: IOException) {
                emptyList<CustomArtworkFile>()
            }
        }

    override suspend fun openWriter(directory: CustomArtworkDirectory, name: String): CustomArtworkWriter? =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(folderOf(directory))
                val stream =
                    Files.newOutputStream(
                        fileOf(directory, name),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                JvmWriter(stream)
            } catch (error: IOException) {
                null
            }
        }

    override suspend fun rename(directory: CustomArtworkDirectory, from: String, to: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val source = fileOf(directory, from)
                val target = fileOf(directory, to)
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (error: AtomicMoveNotSupportedException) {
                    // Some file systems cannot move atomically. Both names sit in one folder, so a plain replace is safe.
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (error: IOException) {
                false
            }
        }

    override suspend fun delete(directory: CustomArtworkDirectory, name: String) {
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(fileOf(directory, name))
            } catch (error: IOException) {
                // Best effort by contract: the next scan removes a superseded version that survived.
            }
        }
    }

    override suspend fun isDecodableImage(directory: CustomArtworkDirectory, name: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                hasImageBounds(fileOf(directory, name).toFile())
            } catch (error: IOException) {
                false
            }
        }

    override fun uriOf(directory: CustomArtworkDirectory, name: String): String =
        fileOf(directory, name).toUri().toString()

    /** True when the first image reader that accepts [file] reports a positive width and height. Reads the header only. */
    private fun hasImageBounds(file: File): Boolean {
        val input = ImageIO.createImageInputStream(file) ?: return false
        return input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use false
            val reader = readers.next()
            try {
                reader.input = stream
                reader.getWidth(0) > 0 && reader.getHeight(0) > 0
            } finally {
                reader.dispose()
            }
        }
    }

    /** The folder of [directory]. A symbolic link here is refused, never followed. */
    private fun folderOf(directory: CustomArtworkDirectory): Path {
        val folder = root.resolve(directory.directoryName)
        if (Files.isSymbolicLink(folder)) throw IOException("Custom artwork folder is a symbolic link")
        return folder
    }

    /** The file [name] inside [directory]. A name that could leave its folder is rejected, never resolved. */
    private fun fileOf(directory: CustomArtworkDirectory, name: String): Path {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Custom artwork name must be a plain file name"
        }
        return folderOf(directory).resolve(name)
    }

    private class JvmWriter(private val stream: OutputStream) : CustomArtworkWriter {
        override suspend fun write(buffer: ByteArray, length: Int) {
            withContext(Dispatchers.IO) { stream.write(buffer, 0, length) }
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) { stream.close() }
        }
    }
}
