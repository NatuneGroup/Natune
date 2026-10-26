/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.playlistimport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchange
import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchangeTrack
import moe.rukamori.archivetune.shared.data.playlistexport.StableWatchLinks
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportException
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportFormat
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportProblem
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportProgress
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportResult
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportSearch
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import kotlin.random.Random

/** A parsed file, held until the user picks a target. [songCount] is the number of tracks the file holds. */
class ParsedPlaylistFile internal constructor(
    internal val format: PlaylistImportFormat,
    internal val tracks: List<PlaylistExchangeTrack>,
    internal val tags: Set<String>,
) {
    val songCount: Int get() = tracks.size
}

/**
 * Reads playlist files and writes the songs they name to an import target. Parsing and writing are separate steps, so
 * the host can show the song count before the user picks a target.
 *
 * Each track is matched in two steps. First the library is asked for the song by identity: a CSV or JSON track by its
 * media id, an M3U track by its stable YouTube watch link. Only when the library holds no such song does a CSV or M3U
 * track fall back to the online song search for "title - artists", as the baseline import did. A JSON track never
 * searches, because its file already names the song. A failed search counts that one track as unmatched and the import
 * goes on. An imported URI is never fetched and never stored as an identity.
 */
class PlaylistImportOwner(
    private val library: PlaylistImportLibrary,
    private val search: PlaylistImportSearch,
    private val newPlaylistId: () -> String = ::randomPlaylistId,
    private val newOccurrenceId: () -> String = ::randomOccurrenceId,
) {
    /** Reads [raw] as [format]. A file of another format, or one with no songs, raises [PlaylistImportException]. */
    fun parse(format: PlaylistImportFormat, raw: String): ParsedPlaylistFile {
        if (raw.length > PlaylistExchange.MAX_CHARACTERS) {
            throw PlaylistImportException(PlaylistImportProblem.UNREADABLE, "Playlist file exceeds the size limit")
        }
        try {
            return when (format) {
                PlaylistImportFormat.CSV -> ParsedPlaylistFile(format, PlaylistImportReaders.csv(raw), emptySet())
                PlaylistImportFormat.M3U -> ParsedPlaylistFile(format, PlaylistImportReaders.m3u(raw), emptySet())
                PlaylistImportFormat.JSON -> PlaylistExchange.readJson(raw).let { ParsedPlaylistFile(format, it.tracks, it.tags) }
            }
        } catch (error: Exception) {
            // The readers refuse a bad file with exceptions of their own. The empty and over-long refusals are told apart by
            // the readers' shared messages, so the host can say the file holds no songs or is too large to read at all.
            val problem = when (error.message) {
                PlaylistExchange.EMPTY_PLAYLIST_MESSAGE -> PlaylistImportProblem.EMPTY
                PlaylistImportReaders.TRACK_LIMIT_MESSAGE -> PlaylistImportProblem.UNREADABLE
                else -> PlaylistImportProblem.NOT_A_PLAYLIST
            }
            throw PlaylistImportException(problem, "Playlist file is not readable as ${format.name}")
        }
    }

    /**
     * Writes the songs of [file] that the library holds, or that the online search finds, to [target], in file order.
     * Nothing is written for an unmatched song. A playlist is created only when at least one song matches. A failed commit
     * throws and leaves the target as it was. [onProgress] is called after each song completes, so calls may arrive in
     * completion order while the counts only grow.
     */
    suspend fun importInto(
        file: ParsedPlaylistFile,
        target: PlaylistImportTarget,
        onProgress: (PlaylistImportProgress) -> Unit = {},
    ): PlaylistImportResult {
        if (target is PlaylistImportTarget.Created) require(target.name.isNotBlank()) { "Playlist name must not be blank" }
        val tracks = file.tracks
        val found = arrayOfNulls<ContentItem>(tracks.size)
        val gate = Semaphore(SEARCH_CONCURRENCY)
        val reportLock = Mutex()
        var processed = 0
        var failed = 0
        coroutineScope {
            tracks.forEachIndexed { index, track ->
                launch {
                    val item = matchTrack(file.format, track, gate)
                    found[index] = item
                    reportLock.withLock {
                        processed++
                        if (item == null) failed++
                        onProgress(PlaylistImportProgress(processed = processed, total = tracks.size, failed = failed))
                    }
                }
            }
        }
        // Results are read back in file order, so the playlist and the failed list follow the file, not the search timing.
        val matched = ArrayList<MatchedSong>()
        val failedTitles = ArrayList<String>()
        tracks.forEachIndexed { index, track ->
            val item = found[index]
            if (item == null) {
                failedTitles += track.label()
            } else {
                matched += MatchedSong(item, track.durationSeconds)
            }
        }
        if (matched.isNotEmpty()) write(matched, target, file.tags)
        return PlaylistImportResult(total = tracks.size, imported = matched.size, failedTitles = failedTitles)
    }

    /** The library first. Only a CSV or M3U track that the library does not hold goes on to the online search. */
    private suspend fun matchTrack(format: PlaylistImportFormat, track: PlaylistExchangeTrack, gate: Semaphore): ContentItem? {
        libraryMatch(format, track)?.let { return it }
        if (format == PlaylistImportFormat.JSON) return null
        val query = searchQuery(track) ?: return null
        return gate.withPermit { onlineMatch(query) }
    }

    /** A song the library already holds: a CSV or JSON track by its media id, an M3U track by its stable watch link. */
    private suspend fun libraryMatch(format: PlaylistImportFormat, track: PlaylistExchangeTrack): ContentItem? = when (format) {
        PlaylistImportFormat.M3U ->
            StableWatchLinks.videoIdOf(track.uri)?.let { library.findSong(it, ContentProvider.YOUTUBE.id) }
        PlaylistImportFormat.CSV, PlaylistImportFormat.JSON ->
            track.mediaId.takeIf(String::isNotBlank)?.let { library.findSong(it, null) }
    }

    /** The first song the search lists. A failed search is one unmatched track, so cancellation is the only error that escapes. */
    private suspend fun onlineMatch(query: String): ContentItem? = try {
        search.firstSong(query)?.takeIf { it.ref.kind == ContentKind.SONG }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun write(matched: List<MatchedSong>, target: PlaylistImportTarget, tags: Set<String>) {
        when (target) {
            is PlaylistImportTarget.Existing -> library.appendImported(target.playlistId, occurrences(matched), tags)
            is PlaylistImportTarget.Created ->
                library.createImportedPlaylist(newPlaylistId(), target.name.trim(), occurrences(matched), tags)
            PlaylistImportTarget.LikedSongs -> library.likeAll(matched.map { it.item }.distinctBy { it.ref })
        }
    }

    /** One occurrence per matched song, in file order. A repeated song gets a fresh key each time, so it is kept twice. */
    private fun occurrences(matched: List<MatchedSong>): List<ContentOccurrence> =
        matched.map { ContentOccurrence(newOccurrenceId(), it.item.withKnownDuration(it.durationSeconds)) }

    /** The file's duration fills a gap only. A duration the library already knows is kept, because it is the playback truth. */
    private fun ContentItem.withKnownDuration(seconds: Int?): ContentItem =
        if (durationMs != null || seconds == null || seconds <= 0) this else copy(durationMs = seconds * 1000L)

    /** The name a failed row is reported under: its title, or else its media id, or else its location. */
    private fun PlaylistExchangeTrack.label(): String = title.ifBlank { mediaId.ifBlank { uri } }

    /** A track that matched, with the duration its file gives for it. */
    private class MatchedSong(val item: ContentItem, val durationSeconds: Int?)
}

/**
 * The baseline search query: "title - artists", or the title alone when no artist is named. Artist names are decoded the
 * way the baseline decoded them. Null when the track has no title, because there is nothing to search for.
 */
internal fun searchQuery(track: PlaylistExchangeTrack): String? {
    val title = track.title.trim()
    if (title.isEmpty()) return null
    val artists = track.artist.split(';')
        .map { decodeArtistName(it.trim()).trim() }
        .filter(String::isNotEmpty)
        .joinToString(" ")
    return if (artists.isEmpty()) title else "$title - $artists"
}

/** The URL-form decoding the baseline applied to each artist name. A name that does not decode is kept exactly as written. */
internal fun decodeArtistName(name: String): String = urlFormDecodeOrNull(name) ?: name

/** "+" is a space and "%XX" is one byte, with the bytes read as UTF-8. Null for a malformed escape or invalid UTF-8. */
private fun urlFormDecodeOrNull(text: String): String? {
    if (text.none { it == '%' || it == '+' }) return text
    val bytes = ArrayList<Byte>(text.length)
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '+' -> {
                bytes += ' '.code.toByte()
                index++
            }
            '%' -> {
                if (index + 2 >= text.length) return null
                val high = hexDigit(text[index + 1]) ?: return null
                val low = hexDigit(text[index + 2]) ?: return null
                bytes += (high * 16 + low).toByte()
                index += 3
            }
            else -> {
                // A run of plain text is encoded as one piece, so a surrogate pair is never split.
                val start = index
                while (index < text.length && text[index] != '%' && text[index] != '+') index++
                for (byte in text.substring(start, index).encodeToByteArray()) bytes += byte
            }
        }
    }
    return try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (error: Exception) {
        null
    }
}

private fun hexDigit(ch: Char): Int? = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> null
}

/** A local playlist id in the baseline shape: "LP" followed by eight ASCII letters and no digits. */
internal fun randomPlaylistId(): String =
    "LP" + CharArray(PLAYLIST_ID_LETTERS) { LETTERS[Random.nextInt(LETTERS.length)] }.concatToString()

/** A random occurrence key for an imported entry. It only has to be unique within its own playlist. */
internal fun randomOccurrenceId(): String =
    "import:" + Random.nextBytes(OCCURRENCE_ID_BYTES).joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The baseline search ran at most this many songs at once. */
private const val SEARCH_CONCURRENCY = 5
private const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val PLAYLIST_ID_LETTERS = 8
private const val OCCURRENCE_ID_BYTES = 12
