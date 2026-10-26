package moe.rukamori.archivetune.shared.domain.localmedia

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

/**
 * The user-facing scan rules, the portable form of the retained `LocalSongScanConfig`. Folder entries normalize
 * case and slashes before matching; include or exclude rules match by equality, path prefix or substring
 * ([matchesFolderEntry]); duration rules drop files shorter than [minimumDurationSeconds].
 */
data class LocalMediaScanConfig(
    val minimumDurationSeconds: Int = 0,
    val includedFolders: List<String> = emptyList(),
    val excludedFolders: List<String> = emptyList(),
) {
    val normalizedIncluded: List<String> = includedFolders.map(::normalizeFolderEntry).filter { it.isNotEmpty() }.distinct()
    val normalizedExcluded: List<String> = excludedFolders.map(::normalizeFolderEntry).filter { it.isNotEmpty() }.distinct()
}

/** The retained folder matching: exact path, path prefix or substring, always on normalized entries. */
fun LocalMediaScanConfig.folderAllowed(folder: String): Boolean {
    val normalized = normalizeFolderEntry(folder)
    if (normalizedExcluded.any { matchesFolderEntry(normalized, it) }) return false
    if (normalizedIncluded.isEmpty()) return true
    return normalizedIncluded.any { matchesFolderEntry(normalized, it) }
}

fun normalizeFolderEntry(entry: String): String =
    entry.trim().replace('\\', '/').removeSuffix("/").lowercase()

fun matchesFolderEntry(candidate: String, entry: String): Boolean =
    candidate == entry || candidate.startsWith("$entry/") || candidate.contains(entry)

/** One scanned audio file the source enumerated. [path] is the host-side identity the artwork/lyrics join reuses. */
data class LocalTrackCandidate(
    val path: String,
    val name: String,
    val folder: String,
    val modifiedEpochMs: Long,
    val sizeBytes: Long,
    val mimeHint: String?,
)

/** What the tag reader extracts; absent fields stay null rather than being invented. */
data class LocalMediaTags(
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val genre: String?,
    /** Embedded artwork bytes after the magic sniff kept only jpg/png/webp payloads. */
    val artwork: ByteArray?,
    val artworkMime: String?,
    /** Embedded lyric text (USLT/SYLT/LYRICS tagged bodies), null when absent. */
    val lyrics: String?,
) {
    override fun equals(other: Any?): Boolean = other is LocalMediaTags &&
        title == other.title && artists == other.artists && album == other.album &&
        albumArtist == other.albumArtist && year == other.year && trackNumber == other.trackNumber &&
        durationMs == other.durationMs && genre == other.genre &&
        (artwork?.contentEquals(other.artwork) ?: (other.artwork == null)) &&
        artworkMime == other.artworkMime && lyrics == other.lyrics

    override fun hashCode(): Int = title.hashCode()
}

/** A track row after normalization and the stable local ids the join derives. */
data class LocalTrackRecord(
    val id: String,
    val path: String,
    val title: String,
    val artists: List<LocalArtistRecord>,
    val album: LocalAlbumRecord?,
    val durationMs: Long?,
    val modifiedEpochMs: Long,
    val sizeBytes: Long,
    val year: Int?,
    val trackNumber: Int?,
    val artworkUri: String?,
    val lyricsText: String?,
)

data class LocalArtistRecord(val id: String, val name: String)
data class LocalAlbumRecord(val id: String, val title: String, val artist: LocalArtistRecord?, val year: Int?)

/** The files the host walks. Desktop lists the chosen roots; iOS lists the imported documents directory. */
interface LocalMediaSource {
    /** Enumerates supported audio files, newest folder first is NOT required; ordering is the join's concern. */
    suspend fun enumerate(): List<LocalTrackCandidate>
    /** Reads the head/tail bytes of one candidate for the tag reader. */
    suspend fun readTagBytes(candidate: LocalTrackCandidate): Pair<ByteArray, ByteArray>
    /** Persists extracted artwork and returns the artwork URI the row carries, or null when none applied. */
    suspend fun storeArtwork(trackId: String, artwork: ByteArray, mime: String): String?
}

/**
 * The library join, the portable form of the retained Room transaction over Song/Artist/Album/Format/Maps/Lyrics.
 * In one transaction it upserts the scan rows as `local`-provider items, drops items whose files vanished, keeps
 * the unchanged rows' artwork, and records the extraction version the next scan compares.
 */
interface LocalMediaLibrary {
    suspend fun extractionVersion(): Int
    suspend fun previousScan(): List<LocalTrackRecord>
    suspend fun commitScan(records: List<LocalTrackRecord>, removedIds: List<String>, extractionVersion: Int): LocalScanSummary
    fun observeLocalSongs(): Flow<List<ContentItem>>
}

data class LocalScanSummary(val scanned: Int, val keptUnchanged: Int, val removed: Int, val upserted: Int)
