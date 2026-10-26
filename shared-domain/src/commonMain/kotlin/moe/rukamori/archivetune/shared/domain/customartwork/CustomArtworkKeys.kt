package moe.rukamori.archivetune.shared.domain.customartwork

import moe.rukamori.archivetune.shared.domain.platform.ContentDigest

/**
 * Keys and file names for custom artwork. A cover is keyed on the artwork it replaces, not on the song row: every image
 * surface requests the same artwork URL, and the sizes of one artwork share one replacement. A canvas is keyed on the
 * song id, because canvas resolution already runs per media id.
 *
 * The keys are the same SHA-256 prefixes the Android store writes, so the files already on a user's disk resolve
 * without a migration. A new version gets a new file name, so an image or video cache can never serve the old file.
 */
object CustomArtworkKeys {
    const val TEMP_SUFFIX: String = ".tmp"

    private const val KEY_LENGTH = 40

    val IMAGE_CANVAS_EXTENSIONS: Set<String> = setOf("gif", "png", "jpg", "jpeg", "webp", "heic", "heif", "avif", "bmp")
    val VIDEO_CANVAS_EXTENSIONS: Set<String> = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")

    private val CANVAS_MIME_EXTENSIONS: Map<String, String> =
        mapOf(
            "image/gif" to "gif",
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/avif" to "avif",
            "image/bmp" to "bmp",
            "video/mp4" to "mp4",
            "video/x-m4v" to "m4v",
            "video/webm" to "webm",
            "video/x-matroska" to "mkv",
            "video/3gpp" to "3gp",
            "video/quicktime" to "mov",
        )

    // The YouTube thumbnail path carries the video id, which is the same for every size of that thumbnail.
    private val YTIMG_VIDEO_ID = Regex("/vi(?:_webp)?/([a-zA-Z0-9_-]{11})/")
    private val GOOGLE_SIZE_SEGMENT = Regex("/w\\d+-h\\d+[^/]*")
    private val SIZED_FILE_NAME = Regex("^\\d+x\\d+[a-z]*(?:-\\d+)?\\.(?:jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
    private val COVER_PATH_MARKER = "/${CustomArtworkDirectory.COVERS.directoryName}/"
    private val CANVAS_PATH_MARKER = "/${CustomArtworkDirectory.CANVASES.directoryName}/"

    /** The key of the cover that replaces [artworkUrl], or null when the URL has no usable identity. */
    fun coverKey(artworkUrl: String?): String? {
        if (artworkUrl.isNullOrBlank()) return null
        val identity = artworkIdentity(artworkUrl) ?: return null
        return hash(identity)
    }

    /** The key of the canvas for [songId], or null for a blank id. */
    fun canvasKey(songId: String?): String? = if (songId.isNullOrBlank()) null else hash(songId)

    /**
     * A size-independent identity for an artwork URL. Callers resize one artwork for lists, the player and the
     * notification, so the size segments are dropped before the key is made. A custom cover path has no identity of its
     * own and returns null.
     */
    fun artworkIdentity(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("file:") && trimmed.contains(COVER_PATH_MARKER)) return null
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = trimmed.substringAfter("://")
        if (scheme != "http" && scheme != "https") return trimmed

        val host = rest.substringBefore('/').lowercase()
        if (host.endsWith("ytimg.com")) {
            YTIMG_VIDEO_ID.find(rest)?.let { return "yt:${it.groupValues[1]}" }
        }
        if (host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")) {
            return "g:" + rest.substringBefore('=').substringBefore('?').replace(GOOGLE_SIZE_SEGMENT, "").trimEnd('/')
        }
        val path = rest.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        return if (SIZED_FILE_NAME.matches(lastSegment)) path.substringBeforeLast('/') else path
    }

    /** `<key>_<epoch millis>[.extension]`: the stored name of one version. */
    fun versionedName(key: String, epochMs: Long, extension: String?): String =
        "${key}_$epochMs" + (extension?.let { ".$it" } ?: "")

    /** The temporary name a copy writes to before it is renamed to its version. */
    fun tempName(key: String): String = key + TEMP_SUFFIX

    /** The key a stored file name belongs to. */
    fun keyOf(fileName: String): String = fileName.substringBefore('_')

    /** The epoch millis of a stored name, or 0 when the name carries none. */
    fun versionOf(fileName: String): Long = fileName.substringAfter('_').substringBefore('.').toLongOrNull() ?: 0L

    /** Leftovers of an interrupted copy, or a file without a version. Both are removed when the folder is scanned. */
    fun isTemporaryOrUnversioned(fileName: String): Boolean = fileName.endsWith(TEMP_SUFFIX) || '_' !in fileName

    /**
     * The extension a canvas is stored under, from its MIME type and then its file extension. A MIME type that is not an
     * image or a video is refused outright. Null means the file is not an accepted canvas.
     */
    fun canvasExtension(mimeType: String?, fileExtension: String?): String? {
        val mime = mimeType?.lowercase()
        if (mime != null && !mime.startsWith("image/") && !mime.startsWith("video/")) return null
        val fromMime = mime?.let { CANVAS_MIME_EXTENSIONS[it] }
        val fromPath = fileExtension?.lowercase()?.takeIf { it.isNotBlank() }
        val extension = fromMime ?: fromPath ?: return null
        return extension.takeIf { it in IMAGE_CANVAS_EXTENSIONS || it in VIDEO_CANVAS_EXTENSIONS }
    }

    fun isImageCanvasExtension(extension: String?): Boolean = extension != null && extension in IMAGE_CANVAS_EXTENSIONS

    /** A `file:` canvas URL inside the canvas folder. */
    fun isCustomCanvasUrl(url: String?): Boolean = url != null && url.startsWith("file:") && url.contains(CANVAS_PATH_MARKER)

    /** A custom canvas whose file is an image rather than a video. */
    fun isCustomImageCanvasUrl(url: String?): Boolean =
        url != null && isCustomCanvasUrl(url) && url.substringAfterLast('.', "").lowercase() in IMAGE_CANVAS_EXTENSIONS

    private fun hash(value: String): String = ContentDigest.sha256(value.encodeToByteArray()).take(KEY_LENGTH)
}
