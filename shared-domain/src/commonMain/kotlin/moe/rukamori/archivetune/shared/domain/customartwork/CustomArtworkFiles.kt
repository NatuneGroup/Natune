package moe.rukamori.archivetune.shared.domain.customartwork

/** One file in an override folder, as the host lists it. The repository reads only the name and the length. */
data class CustomArtworkFile(val name: String, val byteLength: Long)

/**
 * Sequential sink for one copy. The repository closes every writer it opens, including after a failed copy, so the
 * host releases the file handle before a failed temporary file is removed. A failed [write] or [close] may surface as
 * an exception; the repository treats any non-cancellation failure during a copy as [CustomArtworkResult.FAILED].
 */
interface CustomArtworkWriter {
    suspend fun write(buffer: ByteArray, length: Int)

    suspend fun close()
}

/**
 * Host file access for the two override folders. Names are plain file names inside one [CustomArtworkDirectory]; the
 * host confines each name to that folder and never follows it elsewhere. The repository owns the naming, the size
 * limits and the version rules, so this port needs only the file operations the Android store performs.
 *
 * Desktop and Android hosts both implement it over their own file APIs, because the user-picked source and the image
 * decoder are host facilities that commonMain cannot reach.
 */
interface CustomArtworkFiles {
    /** Every regular file in [directory], or an empty list when the folder does not exist yet. */
    suspend fun list(directory: CustomArtworkDirectory): List<CustomArtworkFile>

    /** A writer for [name] that truncates any existing file of that name, or null when it cannot be created. */
    suspend fun openWriter(directory: CustomArtworkDirectory, name: String): CustomArtworkWriter?

    /** Moves [from] to [to] inside [directory], replacing [to]. False when the move failed. */
    suspend fun rename(directory: CustomArtworkDirectory, from: String, to: String): Boolean

    /**
     * Removes [name] when it exists. Best effort: a file that cannot be removed is left for the next scan, which
     * removes superseded versions again, so a failure here never changes the stored state.
     */
    suspend fun delete(directory: CustomArtworkDirectory, name: String)

    /**
     * Whether [name] decodes as an image with a positive width and height. Each host decides which formats it can read.
     * A format it cannot decode is refused as unsupported instead of being stored and then shown broken.
     */
    suspend fun isDecodableImage(directory: CustomArtworkDirectory, name: String): Boolean

    /** A `file:` URI for [name], the form the artwork and canvas loaders already read. */
    fun uriOf(directory: CustomArtworkDirectory, name: String): String
}
