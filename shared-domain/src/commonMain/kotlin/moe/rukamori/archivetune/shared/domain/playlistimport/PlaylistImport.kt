package moe.rukamori.archivetune.shared.domain.playlistimport

import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository

/** The playlist file formats an import reads. JSON is the version-1 archive document. */
enum class PlaylistImportFormat { CSV, M3U, JSON }

/** Why a chosen file cannot be imported. The host maps each value to its own notice. */
enum class PlaylistImportProblem {
    /** The file is not a playlist of the chosen format. */
    NOT_A_PLAYLIST,

    /** The file is a playlist of the chosen format that holds no songs. */
    EMPTY,

    /** The file is larger than any reader accepts, so it cannot be read in one piece. */
    UNREADABLE,
}

/** Raised when a file is refused before any song is matched. Nothing has been written when it is thrown. */
class PlaylistImportException(val problem: PlaylistImportProblem, message: String) : Exception(message)

/** Where an imported playlist goes. */
sealed interface PlaylistImportTarget {
    /** Appends the matched songs to a local playlist that already exists, after the songs it holds. */
    data class Existing(val playlistId: String) : PlaylistImportTarget

    /** Creates a local playlist with this name. The playlist is created only when at least one song matches. */
    data class Created(val name: String) : PlaylistImportTarget

    /** Likes every matched song and creates no playlist. */
    data object LikedSongs : PlaylistImportTarget
}

/** Progress after each song: how many were looked at, how many the file holds and how many did not match. */
data class PlaylistImportProgress(val processed: Int, val total: Int, val failed: Int)

/** [failedTitles] lists, in file order, the songs the library did not hold. Nothing is written for them. */
data class PlaylistImportResult(val total: Int, val imported: Int, val failedTitles: List<String>)

/**
 * The library operations an import needs. Each write is committed as one unit, so a failed commit leaves the library as
 * it was and a partial import never looks complete.
 */
interface PlaylistImportLibrary : LibraryRepository {
    /**
     * The saved song whose media id is [mediaId]. When [provider] is given, only that provider's song counts. Null when
     * the library holds no such song.
     */
    suspend fun findSong(mediaId: String, provider: String?): ContentItem?

    /**
     * Creates a local playlist titled [title] that holds [occurrences] in order and carries the named [tags]. A song the
     * library does not hold yet is saved in the same transaction, as the baseline saves a song before it is added.
     */
    suspend fun createImportedPlaylist(id: String, title: String, occurrences: List<ContentOccurrence>, tags: Set<String>)

    /**
     * Appends [occurrences] to an existing local playlist. [tags] join the tags it already has, and none are removed. A
     * song the library does not hold yet is saved in the same transaction.
     */
    suspend fun appendImported(playlistId: String, occurrences: List<ContentOccurrence>, tags: Set<String>)

    /**
     * Likes every song in [items] in one transaction. A song the library does not hold yet is saved first, so a song found
     * online is liked exactly as a saved one is.
     */
    suspend fun likeAll(items: List<ContentItem>)
}

/**
 * The online song search that an import falls back to when the library holds no match. It returns the first song the
 * provider lists for [query], which is "title - artists" as the baseline builds it, or null when there is none. A
 * transport failure throws; the owner then counts that one song as unmatched and the import goes on.
 */
interface PlaylistImportSearch {
    suspend fun firstSong(query: String): ContentItem?
}
