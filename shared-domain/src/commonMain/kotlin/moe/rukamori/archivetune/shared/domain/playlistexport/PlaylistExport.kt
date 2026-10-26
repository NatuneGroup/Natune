package moe.rukamori.archivetune.shared.domain.playlistexport

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/** A playlist as the export picker sees it. [songCount] decides whether the picker offers it at all. */
data class PlaylistExportCandidate(val ref: ContentRef, val name: String, val songCount: Int)

/** A playlist the export picker offers, with the document name it suggests. */
data class ExportablePlaylist(val ref: ContentRef, val name: String, val songCount: Int, val suggestedFileName: String)

/**
 * One exported track, in the shipped Title, Artist, Album, Media ID order. [artist] is the complete artist text already
 * joined with "; ", so the store decides how a song's several artists are read. [durationSeconds] stays null when the
 * store does not know the length. CSV rows never carry it; the JSON document writes it as null.
 */
data class PlaylistExportTrack(
    val title: String,
    val artist: String,
    val album: String,
    val mediaId: String,
    val durationSeconds: Int? = null,
    /** The library identity of the track. An M3U location is looked up through it, so a store without it has none. */
    val ref: ContentRef? = null,
)

/**
 * The read side of an export. A store reports candidates with real song counts, every track of one playlist and every
 * tag name assigned to it.
 */
interface PlaylistExportSource {
    fun observeCandidates(): Flow<List<PlaylistExportCandidate>>

    /** Every track of [ref] in playlist order, duplicates kept. An empty result means the playlist has nothing to export. */
    suspend fun loadTracks(ref: ContentRef): List<PlaylistExportTrack>

    /** The names of every tag assigned to [ref]. The JSON document carries them, so a missing name is silent data loss. */
    suspend fun loadTags(ref: ContentRef): List<String>
}

/**
 * The write side, bound by the host after the user picks a document. Throwing means the document was not written, and
 * the export then reports failure. A cancelled picker never reaches this call.
 */
interface PlaylistExportDestination {
    suspend fun write(bytes: ByteArray)
}

/**
 * Supplies the M3U location of one track: a playable local file, or a stable provider link. A null or unusable answer
 * refuses the whole export before anything is written, so no row is dropped silently and no location is invented.
 */
interface PlaylistTrackLocations {
    suspend fun locate(track: PlaylistExportTrack): String?
}

/** Raised when a playlist has nothing to export. The picker never offers such a playlist, so this is a stale choice. */
class PlaylistExportException(message: String) : Exception(message)

/** The naming and filter rules that the export picker and every export format share. */
object PlaylistExportNaming {
    private const val MAX_BASE_NAME_CODE_POINTS = 96
    private const val DEFAULT_BASE_NAME = "playlist"

    /** Keeps the playlists that have at least one song, in the order the source supplied. */
    fun exportable(candidates: List<PlaylistExportCandidate>): List<ExportablePlaylist> =
        candidates
            .filter { candidate -> candidate.songCount > 0 }
            .map { candidate ->
                ExportablePlaylist(candidate.ref, candidate.name, candidate.songCount, csvFileName(candidate.name))
            }

    /** The CSV document name that the picker suggests. */
    fun csvFileName(name: String): String = fileName(name, "csv")

    /** The JSON document name. One base-name rule covers every format, so a playlist keeps one name across formats. */
    fun jsonFileName(name: String): String = fileName(name, "json")

    /**
     * The shipped file-name rule: trim the name, replace `\ / : * ? " < > |` and control characters with `_`, drop
     * trailing dots and spaces, keep at most 96 code points, and fall back to "playlist" when nothing is left. The
     * result always ends in [extension].
     */
    private fun fileName(name: String, extension: String): String {
        val replaced = buildString {
            for (ch in name.trim()) {
                append(if (ch in FORBIDDEN_FILE_NAME_CHARACTERS || ch.code <= CONTROL_MAX || ch.code == DELETE) '_' else ch)
            }
        }
        val baseName = replaced.trimEnd('.', ' ').takeCodePoints(MAX_BASE_NAME_CODE_POINTS).ifBlank { DEFAULT_BASE_NAME }
        return "$baseName.$extension"
    }

    private const val FORBIDDEN_FILE_NAME_CHARACTERS = "\\/:*?\"<>|"
    private const val CONTROL_MAX = 0x1F
    private const val DELETE = 0x7F

    /** The first [maxCodePoints] code points. A surrogate pair is kept whole, so the cut never splits a character. */
    private fun String.takeCodePoints(maxCodePoints: Int): String {
        var end = 0
        var count = 0
        while (end < length && count < maxCodePoints) {
            val isPair = this[end].code in HIGH_SURROGATE_FIRST..HIGH_SURROGATE_LAST &&
                end + 1 < length && this[end + 1].code in LOW_SURROGATE_FIRST..LOW_SURROGATE_LAST
            end += if (isPair) 2 else 1
            count++
        }
        return substring(0, end)
    }

    private const val HIGH_SURROGATE_FIRST = 0xD800
    private const val HIGH_SURROGATE_LAST = 0xDBFF
    private const val LOW_SURROGATE_FIRST = 0xDC00
    private const val LOW_SURROGATE_LAST = 0xDFFF
}
