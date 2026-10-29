package moe.rukamori.archivetune.uploads

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadContent
import java.io.Closeable
import java.io.InputStream

/** Display name and byte count of a picked document. A length of -1 means the provider did not report one. */
data class YouTubeUploadDocumentInfo(
    val fileName: String,
    val length: Long,
)

/** Forward-only view of a picked document. It is opened only after the upload policy accepted its name and size. */
class YouTubeUploadDocument(
    val fileName: String,
    override val length: Long,
    private val input: InputStream,
) : YouTubeUploadContent, Closeable {
    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

    override fun close() {
        input.close()
    }
}

/** Reads the display name and size through the Storage Access Framework provider. Blocking; call off the main thread. */
fun describeYouTubeUploadDocument(context: Context, uri: Uri): YouTubeUploadDocumentInfo {
    val resolver = context.contentResolver
    var fileName: String? = null
    var length = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0) fileName = cursor.getString(nameColumn)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) length = cursor.getLong(sizeColumn)
        }
    }
    if (length <= 0L) {
        // Some providers report no size through the cursor but do through the descriptor.
        length = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    }
    return YouTubeUploadDocumentInfo(
        fileName = fileName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty(),
        length = length,
    )
}

/** Opens the document for streaming, or returns null when the provider refuses the read. */
fun openYouTubeUploadDocument(context: Context, uri: Uri, info: YouTubeUploadDocumentInfo): YouTubeUploadDocument? {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    return YouTubeUploadDocument(info.fileName, info.length, input)
}
