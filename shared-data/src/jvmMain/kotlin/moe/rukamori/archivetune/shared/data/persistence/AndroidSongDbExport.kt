package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong

/**
 * Writes a portable state snapshot as an Android `song.db` at Room schema 38, the reverse of [LegacySongDbImport], so
 * a desktop library restores in the Android app.
 *
 * The Android library holds YouTube songs, albums and artists only. Items of any other provider, and this host's
 * local files, whose paths mean nothing on a phone, are left out and counted in [Outcome.skippedItems].
 *
 * The portable database orders library rows by sequence numbers. A sequence imported from Android is the original
 * epoch time and is written back unchanged; a sequence this host counted is turned into a time one second apart per
 * step, ending at the export time, so the Android date orders match the desktop orders.
 *
 * Provider caches the importer skips (`format`, `lyrics`, `related_song_map`, `playCount`, the top mix) stay empty;
 * Android fills them again.
 */
object AndroidSongDbExport {
    const val ANDROID_SCHEMA_VERSION = LegacySongDbImport.ANDROID_SCHEMA_VERSION

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
        val podcastProgress: Int,
        val songMetadata: Int,
        val skippedItems: Int,
    )

    /**
     * Writes [portable], a snapshot of the current portable schema, to [target], which must not exist. The file is
     * built beside [target] and moved onto it only once complete.
     */
    suspend fun export(portable: Path, target: Path, nowMs: Long = System.currentTimeMillis()): Outcome =
        withContext(Dispatchers.IO) {
            require(target.isAbsolute && !Files.exists(target)) { "Export target must be a new absolute path" }
            require(inspectPortableStateFile(portable) == PORTABLE_STATE_SCHEMA_VERSION) {
                "Portable snapshot is not at the current schema"
            }
            val model = AndroidLibraryModel(nowMs).also { read(portable, it) }
            val parent = requireNotNull(target.toAbsolutePath().parent)
            val staging = Files.createTempFile(parent, "android-export-", ".db")
            try {
                val connection = BundledSQLiteDriver().open(staging.toString())
                try {
                    connection.exec("PRAGMA foreign_keys = ON")
                    connection.exec("BEGIN")
                    schemaStatements().forEach(connection::exec)
                    write(connection, model)
                    connection.exec("COMMIT")
                    connection.prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "Export broke a foreign key" } }
                    connection.exec("PRAGMA user_version = $ANDROID_SCHEMA_VERSION")
                } finally {
                    connection.close()
                }
                Files.move(staging, target)
                model.outcome()
            } finally {
                Files.deleteIfExists(staging)
                Files.deleteIfExists(Path.of("$staging-journal"))
            }
        }

    /** The schema-38 DDL, the views and Room's identity row, exported from `app/schemas`. */
    private fun schemaStatements(): List<String> =
        requireNotNull(AndroidSongDbExport::class.java.getResourceAsStream(SCHEMA_RESOURCE)) { "Android schema missing" }
            .use { it.readBytes().decodeToString() }
            .lines().map(String::trim).filter(String::isNotEmpty)

    private fun read(portable: Path, model: AndroidLibraryModel) {
        val connection = BundledSQLiteDriver().open(portable.toString())
        try {
            connection.prepare(
                "SELECT provider, kind, mediaId, json, liked, addedSeq, likedSeq FROM library_item " +
                    "ORDER BY addedSeq, provider, mediaId",
            ).use { statement ->
                while (statement.step()) {
                    val item = decodeItem(statement.getText(3))
                    if (item == null || statement.getText(0) != YOUTUBE) {
                        model.skippedItems += 1
                        continue
                    }
                    model.libraryItem(
                        statement.getText(1), item, statement.getLong(4) != 0L, statement.getLong(5), statement.getLong(6),
                    )
                }
            }
            model.resolveLibrary()
            connection.prepare(
                "SELECT id, title, createdSeq, songSortType, songSortDescending, isHidden FROM portable_playlist " +
                    "ORDER BY COALESCE(createdSeq, 0), id",
            ).use { statement ->
                while (statement.step()) {
                    model.playlists += ExportedPlaylist(
                        id = statement.getText(0),
                        title = statement.getText(1),
                        createdSeq = if (statement.isNull(2)) null else statement.getLong(2),
                        songSortType = statement.getText(3),
                        songSortDescending = statement.getLong(4) != 0L,
                        hidden = statement.getLong(5) != 0L,
                    )
                }
            }
            connection.prepare("SELECT playlistId, json FROM portable_playlist_entry ORDER BY playlistId, position, addedSeq")
                .use { statement ->
                    while (statement.step()) {
                        val occurrence = decodeOccurrence(statement.getText(1))
                        val item = occurrence?.item?.takeIf(::isYouTubeSong)
                        if (item == null) {
                            model.skippedItems += 1
                            continue
                        }
                        model.playlistEntry(statement.getText(0), item, occurrence.setVideoId)
                    }
                }
            connection.prepare("SELECT occurrenceJson, timestampMs, playTimeMs FROM portable_listen_event ORDER BY timestampMs, id")
                .use { statement ->
                    while (statement.step()) {
                        val item = decodeOccurrence(statement.getText(0))?.item?.takeIf(::isYouTubeSong)
                        if (item == null) {
                            model.skippedItems += 1
                            continue
                        }
                        model.listen(item, statement.getLong(1), statement.getLong(2))
                    }
                }
            connection.prepare("SELECT provider, artistId, artistName, blockedAtMs FROM portable_artist_block").use { statement ->
                while (statement.step()) {
                    if (statement.getText(0) != YOUTUBE) {
                        model.skippedItems += 1
                        continue
                    }
                    model.blockArtist(statement.getText(1), statement.getText(2), statement.getLong(3))
                }
            }
            connection.prepare("SELECT id, name, color FROM portable_tag ORDER BY rowid").use { statement ->
                while (statement.step()) model.tags += Triple(statement.getText(0), statement.getText(1), statement.getText(2))
            }
            connection.prepare("SELECT playlistId, tagId FROM portable_playlist_tag").use { statement ->
                while (statement.step()) model.assignments += statement.getText(0) to statement.getText(1)
            }
            connection.prepare("SELECT `query` FROM portable_search_history ORDER BY id").use { statement ->
                while (statement.step()) model.searches += statement.getText(0)
            }
            connection.prepare("SELECT videoId, setVideoId FROM portable_set_video_id").use { statement ->
                while (statement.step()) {
                    model.setVideoIds += statement.getText(0) to (if (statement.isNull(1)) null else statement.getText(1))
                }
            }
            connection.prepare(
                "SELECT episodeId, positionMs, durationMs, finished, updatedAtMs FROM portable_podcast_progress",
            ).use { statement ->
                while (statement.step()) {
                    model.progress += ExportedProgress(
                        statement.getText(0), statement.getLong(1),
                        if (statement.isNull(2)) null else statement.getLong(2),
                        statement.getLong(3) != 0L, statement.getLong(4),
                    )
                }
            }
            connection.prepare(
                "SELECT provider, songId, title, artistNamesJson, updatedAtMs FROM portable_song_metadata_override",
            ).use { statement ->
                while (statement.step()) {
                    if (statement.getText(0) != YOUTUBE) continue
                    val artists = if (statement.isNull(3)) {
                        null
                    } else {
                        runCatching {
                            Json.parseToJsonElement(statement.getText(3)).jsonArray.map { it.jsonPrimitive.content }
                        }.getOrNull()
                    }
                    model.metadata += ExportedMetadata(
                        statement.getText(1),
                        if (statement.isNull(2)) null else statement.getText(2),
                        artists?.joinToString("\n"),
                        statement.getLong(4),
                    )
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun write(connection: SQLiteConnection, model: AndroidLibraryModel) {
        val now = model.nowMs
        connection.each(
            "INSERT INTO artist (id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt, blockedAt, isLocal) " +
                "VALUES (?, ?, ?, NULL, ?, ?, ?, 0)",
            model.artists.values,
        ) { artist -> bindAll(artist.id, artist.name, artist.thumbnailUrl, now, artist.bookmarkedAt, artist.blockedAt) }
        connection.each(
            "INSERT INTO album (id, playlistId, title, year, thumbnailUrl, themeColor, songCount, duration, explicit, " +
                "lastUpdateTime, bookmarkedAt, likedDate, inLibrary, isLocal) VALUES (?, NULL, ?, NULL, ?, NULL, 0, 0, ?, ?, ?, NULL, NULL, 0)",
            model.albums.values,
        ) { album -> bindAll(album.item.ref.id, album.item.title, album.item.artworkUri, album.item.explicit, now, album.bookmarkedAt) }
        connection.each(
            "INSERT INTO song (id, title, titleOverride, duration, thumbnailUrl, albumId, albumName, explicit, year, date, " +
                "dateModified, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isMusicVideo, isLocal, blockedAt) " +
                "VALUES (?, ?, 0, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, NULL, ?, 0, NULL)",
            model.songs.values,
        ) { song ->
            val item = song.item
            bindAll(
                item.ref.id, item.title, (item.durationMs ?: 0L) / 1000L, item.artworkUri, item.albumId, item.album,
                item.explicit, song.liked, song.likedDate, song.totalPlayTimeMs, song.inLibrary, item.isVideo,
            )
        }
        connection.each(
            "INSERT OR IGNORE INTO song_artist_map (songId, artistId, position) VALUES (?, ?, ?)",
            model.songs.values.flatMap { song -> song.artistIds.mapIndexed { index, id -> Triple(song.item.ref.id, id, index) } },
        ) { (songId, artistId, position) -> bindAll(songId, artistId, position) }
        val playlistIds = model.playlists.mapTo(HashSet()) { it.id }
        val createdTimes = SequenceTimes(model.playlists.mapNotNull { it.createdSeq }, now)
        connection.each(
            "INSERT INTO playlist (id, name, browseId, createdAt, lastUpdateTime, isEditable, bookmarkedAt, remoteSongCount, " +
                "playEndpointParams, thumbnailUrl, shuffleEndpointParams, radioEndpointParams, customOrder, isLocal, isAutoSync, " +
                "songSortType, songSortDescending, isHidden) " +
                "VALUES (?, ?, NULL, ?, ?, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, ?, ?, ?)",
            model.playlists,
        ) { playlist ->
            bindAll(
                playlist.id, playlist.title, playlist.createdSeq?.let(createdTimes::at) ?: now, now,
                playlist.songSortType, playlist.songSortDescending, playlist.hidden,
            )
        }
        val entries = model.entries.filter { it.playlistId in playlistIds }
        connection.each(
            "INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId) VALUES (?, ?, ?, ?)",
            entries,
        ) { entry -> bindAll(entry.playlistId, entry.songId, entry.position, entry.setVideoId) }
        connection.each("INSERT INTO event (songId, timestamp, playTime) VALUES (?, ?, ?)", model.events) { event ->
            bindAll(event.songId, event.timestampMs, event.playTimeMs)
        }
        val tagIds = model.tags.mapTo(HashSet()) { it.first }
        connection.each("INSERT OR IGNORE INTO tag (id, name, color, createdAt) VALUES (?, ?, ?, ?)", model.tags) { (id, name, color) ->
            bindAll(id, name, color, now)
        }
        val assignments = model.assignments.filter { (playlistId, tagId) -> playlistId in playlistIds && tagId in tagIds }
        connection.each(
            "INSERT OR IGNORE INTO playlist_tag_map (playlistId, tagId, createdAt) VALUES (?, ?, ?)",
            assignments,
        ) { (playlistId, tagId) -> bindAll(playlistId, tagId, now) }
        connection.each("INSERT OR IGNORE INTO search_history (`query`) VALUES (?)", model.searches) { query -> bindAll(query) }
        connection.each("INSERT OR REPLACE INTO set_video_id (videoId, setVideoId) VALUES (?, ?)", model.setVideoIds) { (video, set) ->
            bindAll(video, set)
        }
        connection.each(
            "INSERT OR REPLACE INTO podcast_progress (episodeId, positionMs, durationMs, finished, updatedAtMs) VALUES (?, ?, ?, ?, ?)",
            model.progress,
        ) { row -> bindAll(row.episodeId, row.positionMs, row.durationMs, row.finished, row.updatedAtMs) }
        val metadata = model.metadata.filter { it.songId in model.songs && (it.title != null || it.artistNames != null) }
        connection.each(
            "INSERT OR REPLACE INTO song_metadata_override (songId, title, artistNames, updatedAt) VALUES (?, ?, ?, ?)",
            metadata,
        ) { row -> bindAll(row.songId, row.title, row.artistNames, row.updatedAtMs) }
        model.written = Written(
            playlistEntries = entries.size, tagAssignments = assignments.size, songMetadata = metadata.size,
        )
    }

    private fun decodeOccurrence(json: String): ContentOccurrence? =
        runCatching { ContentJson.decode(Json.parseToJsonElement(json).jsonObject) }.getOrNull()

    private fun decodeItem(json: String): ContentItem? = decodeOccurrence(json)?.item

    private fun isYouTubeSong(item: ContentItem): Boolean = item.ref.provider == YOUTUBE && item.ref.kind == ContentKind.SONG

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private inline fun <T> SQLiteConnection.each(sql: String, rows: Iterable<T>, bind: SQLiteStatement.(T) -> Unit) {
        prepare(sql).use { statement ->
            for (row in rows) {
                statement.bind(row)
                statement.step()
                statement.reset()
                statement.clearBindings()
            }
        }
    }

    private fun SQLiteStatement.bindAll(vararg values: Any?) {
        values.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                null -> bindNull(index)
                is String -> bindText(index, value)
                is Long -> bindLong(index, value)
                is Int -> bindLong(index, value.toLong())
                is Boolean -> bindLong(index, if (value) 1L else 0L)
                else -> throw IllegalArgumentException("Unsupported column value")
            }
        }
    }

    private val YOUTUBE = ContentProvider.YOUTUBE.id
    private const val SCHEMA_RESOURCE = "/android-schema/song-db-38.sql"
}

/**
 * Turns portable sequence numbers into Android times. Values at or after [EPOCH_FLOOR_MS] are already epoch times; the
 * others keep their order, one second apart, ending at [nowMs]. [Long.MAX_VALUE], the importer's unknown time, and
 * values below one have no time.
 */
internal class SequenceTimes(values: Collection<Long>, private val nowMs: Long) {
    private val counters: List<Long> = values.filter { it in 1 until EPOCH_FLOOR_MS }.distinct().sorted()

    fun at(sequence: Long): Long? = when {
        sequence == Long.MAX_VALUE || sequence < 1 -> null
        sequence >= EPOCH_FLOOR_MS -> sequence
        else -> {
            val rank = counters.binarySearch(sequence).let { if (it < 0) -(it + 1) else it }
            nowMs - (counters.size - rank).toLong() * 1000L
        }
    }

    companion object {
        /** 2000-01-01 UTC. No desktop counter reaches it, and no Android library predates it. */
        const val EPOCH_FLOOR_MS = 946_684_800_000L
    }
}

private class ExportedSong(val item: ContentItem, val artistIds: List<String>) {
    var liked = false
    var likedDate: Long? = null
    var inLibrary: Long? = null
    var totalPlayTimeMs = 0L
}

private class ExportedArtist(val id: String, var name: String, var thumbnailUrl: String?) {
    var bookmarkedAt: Long? = null
    var blockedAt: Long? = null
}

private class ExportedAlbum(val item: ContentItem, val bookmarkedAt: Long)

private class ExportedPlaylist(
    val id: String,
    val title: String,
    val createdSeq: Long?,
    val songSortType: String,
    val songSortDescending: Boolean,
    val hidden: Boolean,
)

private class ExportedEntry(val playlistId: String, val songId: String, val position: Int, val setVideoId: String?)

private class ExportedEvent(val songId: String, val timestampMs: Long, val playTimeMs: Long)

private class ExportedProgress(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val finished: Boolean,
    val updatedAtMs: Long,
)

private class ExportedMetadata(val songId: String, val title: String?, val artistNames: String?, val updatedAtMs: Long)

private class Written(val playlistEntries: Int, val tagAssignments: Int, val songMetadata: Int)

/** The Android rows built from the portable snapshot, keyed the way the Android tables key them. */
private class AndroidLibraryModel(val nowMs: Long) {
    val songs = LinkedHashMap<String, ExportedSong>()
    val artists = LinkedHashMap<String, ExportedArtist>()
    val albums = LinkedHashMap<String, ExportedAlbum>()
    val playlists = ArrayList<ExportedPlaylist>()
    val entries = ArrayList<ExportedEntry>()
    val events = ArrayList<ExportedEvent>()
    val tags = ArrayList<Triple<String, String, String>>()
    val assignments = ArrayList<Pair<String, String>>()
    val searches = ArrayList<String>()
    val setVideoIds = ArrayList<Pair<String, String?>>()
    val progress = ArrayList<ExportedProgress>()
    val metadata = ArrayList<ExportedMetadata>()
    var skippedItems = 0
    var written: Written? = null

    private val pendingLibrary = ArrayList<PendingLibraryRow>()
    private val positions = HashMap<String, Int>()
    private var librarySongs = 0
    private var bookmarkedArtists = 0
    private var blockedArtists = 0

    private class PendingLibraryRow(val kind: String, val item: ContentItem, val liked: Boolean, val added: Long, val likedSeq: Long)

    fun libraryItem(kind: String, item: ContentItem, liked: Boolean, addedSeq: Long, likedSeq: Long) {
        if (kind != ContentKind.SONG.name && kind != ContentKind.ALBUM.name && kind != ContentKind.ARTIST.name) {
            skippedItems += 1
            return
        }
        pendingLibrary += PendingLibraryRow(kind, item, liked, addedSeq, likedSeq)
    }

    fun playlistEntry(playlistId: String, item: ContentItem, setVideoId: String?) {
        song(item)
        val position = positions.getOrDefault(playlistId, 0)
        positions[playlistId] = position + 1
        entries += ExportedEntry(playlistId, item.ref.id, position, setVideoId)
    }

    fun listen(item: ContentItem, timestampMs: Long, playTimeMs: Long) {
        song(item).totalPlayTimeMs += playTimeMs
        events += ExportedEvent(item.ref.id, timestampMs, playTimeMs)
    }

    fun blockArtist(id: String, name: String, blockedAtMs: Long) {
        val artist = artists.getOrPut(id) { ExportedArtist(id, name, null) }
        if (artist.blockedAt == null) blockedArtists += 1
        artist.blockedAt = blockedAtMs
    }

    fun outcome(): AndroidSongDbExport.Outcome {
        val written = requireNotNull(written)
        return AndroidSongDbExport.Outcome(
            librarySongs = librarySongs, likedAlbums = albums.size, bookmarkedArtists = bookmarkedArtists,
            blockedArtists = blockedArtists, playlists = playlists.size, playlistEntries = written.playlistEntries,
            listenEvents = events.size, tags = tags.size, tagAssignments = written.tagAssignments, searches = searches.size,
            setVideoIds = setVideoIds.size, podcastProgress = progress.size, songMetadata = written.songMetadata,
            skippedItems = skippedItems,
        )
    }

    /** Resolves the sequence times once every library row is known, so each kind is ordered among its own rows. */
    fun resolveLibrary() {
        val added = SequenceTimes(pendingLibrary.map { it.added }, nowMs)
        val likedTimes = SequenceTimes(pendingLibrary.filter { it.liked }.map { it.likedSeq }, nowMs)
        for (row in pendingLibrary) {
            when (row.kind) {
                ContentKind.SONG.name -> {
                    val song = song(row.item)
                    song.liked = row.liked
                    song.likedDate = if (row.liked) likedTimes.at(row.likedSeq) else null
                    song.inLibrary = added.at(row.added) ?: if (row.liked) null else nowMs
                    librarySongs += 1
                }
                ContentKind.ALBUM.name -> albums[row.item.ref.id] = ExportedAlbum(
                    row.item, likedTimes.at(row.likedSeq) ?: added.at(row.added) ?: nowMs,
                )
                ContentKind.ARTIST.name -> {
                    val artist = artists.getOrPut(row.item.ref.id) {
                        ExportedArtist(row.item.ref.id, row.item.title, row.item.artworkUri)
                    }
                    artist.name = row.item.title.ifBlank { artist.name }
                    artist.thumbnailUrl = row.item.artworkUri ?: artist.thumbnailUrl
                    if (artist.bookmarkedAt == null) bookmarkedArtists += 1
                    artist.bookmarkedAt = likedTimes.at(row.likedSeq) ?: added.at(row.added) ?: nowMs
                }
            }
        }
        pendingLibrary.clear()
    }

    /** The canonical row: display-name edits go to `song_metadata_override`, as the Android app keeps them. */
    private fun song(item: ContentItem): ExportedSong = songs.getOrPut(item.ref.id) {
        val canonical = item.canonicalSong()
        val ids = canonical.artists.filter { it.name.isNotBlank() }.map { credit ->
            val id = credit.id?.takeIf(String::isNotBlank) ?: generatedArtistId(credit.name)
            artists.getOrPut(id) { ExportedArtist(id, credit.name, null) }
            id
        }.distinct()
        ExportedSong(canonical, ids)
    }

    /**
     * A credit without a provider id gets the Android local-artist form, `LA` and eight letters or digits, derived from
     * the name so every song by that name shares one artist row.
     */
    private fun generatedArtistId(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.trim().lowercase().encodeToByteArray())
        return "LA" + digest.take(8).joinToString("") { byte -> ID_ALPHABET[(byte.toInt() and 0xFF) % ID_ALPHABET.length].toString() }
    }

    private companion object {
        const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
