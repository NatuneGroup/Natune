/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFile
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkWriter

/**
 * Android custom-artwork files. The root is the app files directory, the same one the retained `CustomArtworkStore`
 * writes to, so covers and canvases picked before the port keep resolving without a migration.
 *
 * The decodability check uses the same bounds-only `BitmapFactory` decode as the retained store, so an image format
 * Android cannot read is refused rather than stored.
 */
class AndroidCustomArtworkFiles(context: Context) : CustomArtworkFiles {
    private val root: File = context.applicationContext.filesDir

    override suspend fun list(directory: CustomArtworkDirectory): List<CustomArtworkFile> =
        withContext(Dispatchers.IO) {
            folderOf(directory).listFiles()?.filter { it.isFile }?.map { CustomArtworkFile(it.name, it.length()) }
                ?: emptyList()
        }

    override suspend fun openWriter(directory: CustomArtworkDirectory, name: String): CustomArtworkWriter? =
        withContext(Dispatchers.IO) {
            try {
                val folder = folderOf(directory)
                if (!folder.isDirectory && !folder.mkdirs()) return@withContext null
                // File.outputStream truncates, so a temporary file left by an interrupted copy is replaced.
                AndroidCustomArtworkWriter(File(folder, checkedName(name)).outputStream())
            } catch (error: IOException) {
                null
            }
        }

    override suspend fun rename(directory: CustomArtworkDirectory, from: String, to: String): Boolean =
        withContext(Dispatchers.IO) {
            val folder = folderOf(directory)
            File(folder, checkedName(from)).renameTo(File(folder, checkedName(to)))
        }

    override suspend fun delete(directory: CustomArtworkDirectory, name: String) {
        withContext(Dispatchers.IO) {
            File(folderOf(directory), checkedName(name)).delete()
            Unit
        }
    }

    override suspend fun isDecodableImage(directory: CustomArtworkDirectory, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(folderOf(directory), checkedName(name))
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                BitmapFactory.decodeFile(file.absolutePath, options)
            } catch (error: Exception) {
                return@withContext false
            }
            options.outWidth > 0 && options.outHeight > 0
        }

    override fun uriOf(directory: CustomArtworkDirectory, name: String): String =
        Uri.fromFile(File(folderOf(directory), checkedName(name))).toString()

    private fun folderOf(directory: CustomArtworkDirectory): File = File(root, directory.directoryName)

    private fun checkedName(name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Custom artwork name must be a plain file name"
        }
        return name
    }
}

/**
 * A file the user picked in the system picker, read through the content resolver. The MIME type is a content-provider
 * call, so the repository must be driven from a background dispatcher, never from the main thread.
 */
class AndroidCustomArtworkSource(private val context: Context, private val uri: Uri) : CustomArtworkSource {
    override val mimeType: String?
        get() = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    override val fileExtension: String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    override suspend fun open(): CustomArtworkReader? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.let { AndroidCustomArtworkReader(it) }
            } catch (error: Exception) {
                // A picker grant that expired or a provider that refused the file is an unreadable source, not a crash.
                null
            }
        }
}

private class AndroidCustomArtworkReader(private val stream: InputStream) : CustomArtworkReader {
    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { stream.read(buffer) }

    override suspend fun close() {
        withContext(Dispatchers.IO) { stream.close() }
    }
}

private class AndroidCustomArtworkWriter(private val stream: OutputStream) : CustomArtworkWriter {
    override suspend fun write(buffer: ByteArray, length: Int) {
        withContext(Dispatchers.IO) { stream.write(buffer, 0, length) }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { stream.close() }
    }
}
