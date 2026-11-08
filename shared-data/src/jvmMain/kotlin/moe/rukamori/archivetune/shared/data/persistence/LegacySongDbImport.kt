package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistSongOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * Imports an Android `song.db` backup file (up to Room schema 39) into the portable state database the desktop
 * and iOS hosts keep. Every table the baseline persists is mapped, so a restored archive loses nothing the portable
 * schema can express:
 *
 * - `song` rows with `inLibrary` or `liked` set become `library_item` songs; `inLibrary` and `likedDate` supply the
 *   add and like sequences the CREATE_DATE and liked orders read. Songs blocked in the baseline carry a `blockedAt`
 *   the portable schema has no table for, so the flag is dropped; the songs themselves are kept.
 * - `artist` rows with `bookmarkedAt` become liked artist items, `blockedAt` becomes `portable_artist_block`.
 * - `album` rows with `bookmarkedAt`, `likedDate` or `inLibrary` become liked album items.
 * - `playlist` rows keep their ids and per-playlist sort, `playlist_song_map` rows keep order and `setVideoId`, and a
 *   playlist member is not forced into the library, matching the baseline where `inLibrary` stays unset.
 * - `event` rows become `portable_listen_event` with the same occurrence JSON a live listen writes, plus the artist
 *   and album credit rows, so history, statistics and PLAY_TIME order see the plays the baseline recorded.
 * - `tag` and `playlist_tag_map` keep tag ids, names, colours and assignments. `search_history` and `set_video_id`
 *   map one to one onto the v8 tables.
 * - `podcast_progress` bookmarks and played markers map to `portable_podcast_progress`, including episodes that
 *   have never been saved to the library.
 * - Display-name edits, including pre-38 `titleOverride` titles, remain separate from canonical library/queue JSON.
 *
 * Provider reads (`format`, `lyrics`, `related_song_map`, the remote endpoint params on playlists and albums, the
 * generated `library_top_mix` content and `playCount`, which this fork never writes and which the statistics derive
 * from events instead) are deliberately not imported: they are caches and server state the host re-fetches.
 * `isHidden` is kept on `portable_playlist` so a hidden playlist survives a round trip even though no portable
 * surface reads the flag yet.
 * - `song.lyricsOffset` becomes `portable_song_lyrics_offset`, keeping the per-song sync correction across
 *   hosts; the column exists only from schema 39 on, so older backups simply carry no offsets.
 *
 * The result is written to a scratch file, verified and then moved onto [target] in one rename, so a failed import
 * never leaves a partial database behind.
 */
object LegacySongDbImport {
    const val ANDROID_SCHEMA_VERSION = 39

    /** Number of source rows the importer wrote and could not write. */
    data class Outcome(
        val librarySongs: Int,
        val likedAlbums: Int,
        val bookmarkedArtists: Int,
        val blockedArtists: Int,
        val playlists: Int,
        val playlistEntries: Int,
        val listenEvents: Int,
        val tags: Int,
        val tagAssignments: Int,
        val searches: Int,
        val setVideoIds: Int,
        val droppedEntries: Int,
        val podcastProgress: Int = 0,
        val lyricsOffsets: Int = 0,
    )

    /**
     * Converts the Android database at [songDb] into a new portable state file at [target]. [target] must not exist.
     * Throws [IllegalArgumentException] when the file is not a usable `song.db` this importer understands.
     */
    suspend fun convert(songDb: Path, target: Path): Outcome = withContext(Dispatchers.IO) {
        require(target.isAbsolute && !Files.exists(target)) { "Import target must be a new absolute path" }
        val parent = requireNotNull(target.toAbsolutePath().parent)
        val staging = Files.createTempFile(parent, "portable-import-", ".db")
        try {
            var outcome: PortableAndroidLibraryImport.Outcome? = null
            val database = openPortableStateDatabase(staging.toString())
            try {
                outcome = PortableAndroidLibraryImport.copy(BundledSQLiteDriver().open(songDb.toString()), database)
            } finally {
                database.close()
            }
            inspectPortableStateFile(staging)
            val connection = BundledSQLiteDriver().open(staging.toString())
            try {
                connection.execSql("VACUUM INTO \"${target.toAbsolutePath().toString().replace("\"", "\"\"")}\"")
            } finally {
                connection.close()
            }
            requireNotNull(outcome).let { Outcome(it.librarySongs, it.likedAlbums, it.bookmarkedArtists,
                it.blockedArtists, it.playlists, it.playlistEntries, it.listenEvents, it.tags, it.tagAssignments,
                it.searches, it.setVideoIds, it.droppedEntries, it.podcastProgress, it.lyricsOffsets) }
        } finally {
            Files.deleteIfExists(staging)
            Files.deleteIfExists(Path.of("$staging-wal"))
            Files.deleteIfExists(Path.of("$staging-shm"))
        }
    }

}
