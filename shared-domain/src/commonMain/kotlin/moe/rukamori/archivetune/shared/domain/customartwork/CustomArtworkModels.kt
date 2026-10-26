package moe.rukamori.archivetune.shared.domain.customartwork

/** The outcome of saving a custom cover or canvas. Only [SAVED] changes what the user sees. */
enum class CustomArtworkResult { SAVED, TOO_LARGE, UNSUPPORTED, FAILED }

/**
 * Size ceilings for a user-picked file. The copy stops at the limit, so an oversized file is never stored in full and
 * the stored file is never checked after the fact.
 */
object CustomArtworkLimits {
    const val MAX_COVER_BYTES: Long = 20L * 1024 * 1024
    const val MAX_CANVAS_BYTES: Long = 50L * 1024 * 1024
}

/**
 * The two override folders. The names are the Android folder names under the app files directory, so a host that
 * shares the same root reads the same files.
 */
enum class CustomArtworkDirectory(val directoryName: String) {
    COVERS("custom_artwork"),
    CANVASES("custom_canvas"),
}

/**
 * The stored overrides. A cover is keyed by the artwork it replaces, a canvas by its song id, and each value is the
 * plain file name inside that folder. Callers ask through [CustomArtworkKeys] rather than building keys themselves.
 */
data class CustomArtworkOverrides(
    val covers: Map<String, String> = emptyMap(),
    val canvases: Map<String, String> = emptyMap(),
) {
    fun hasCover(artworkUrl: String?): Boolean {
        if (covers.isEmpty()) return false
        val key = CustomArtworkKeys.coverKey(artworkUrl) ?: return false
        return key in covers
    }

    fun hasCanvas(songId: String?): Boolean {
        if (canvases.isEmpty()) return false
        val key = CustomArtworkKeys.canvasKey(songId) ?: return false
        return key in canvases
    }
}

/**
 * A bounded, sequential reader over the bytes the user picked. [read] fills the buffer and returns the number of bytes,
 * or -1 at the end. It must block until at least one byte or the end is available, because a zero result is treated as
 * the end of the input.
 */
interface CustomArtworkReader {
    suspend fun read(buffer: ByteArray): Int

    suspend fun close()
}

/**
 * A file the user picked, as the host sees it. [mimeType] and [fileExtension] are hints used only to choose a canvas
 * extension. The bytes decide nothing beyond the decodability check that covers and image canvases pass.
 */
interface CustomArtworkSource {
    val mimeType: String?

    /** The text after the last dot of the file name, or null when the name has none. */
    val fileExtension: String?

    /** A fresh reader, or null when the host cannot open the file. The caller closes what it opens. */
    suspend fun open(): CustomArtworkReader?
}
