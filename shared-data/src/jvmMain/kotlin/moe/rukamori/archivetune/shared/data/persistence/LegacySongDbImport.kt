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
 * Imports an Android `song.db` backup file (up to Room schema 38) into the portable state database the desktop
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
 *
 * The result is written to a scratch file, verified and then moved onto [target] in one rename, so a failed import
 * never leaves a partial database behind.
 */
object LegacySongDbImport {
    const val ANDROID_SCHEMA_VERSION = 38

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
    )

    /**
     * Converts the Android database at [songDb] into a new portable state file at [target]. [target] must not exist.
     * Throws [IllegalArgumentException] when the file is not a usable `song.db` this importer understands.
     */
    suspend fun convert(songDb: Path, target: Path): Outcome = withContext(Dispatchers.IO) {
        require(target.isAbsolute && !Files.exists(target)) { "Import target must be a new absolute path" }
        val source = read(songDb)
        val parent = requireNotNull(target.toAbsolutePath().parent)
        val staging = Files.createTempFile(parent, "portable-import-", ".db")
        try {
            val database = openPortableStateDatabase(staging.toString())
            try {
                write(database, source)
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
            source.outcome
        } finally {
            Files.deleteIfExists(staging)
            Files.deleteIfExists(Path.of("$staging-wal"))
            Files.deleteIfExists(Path.of("$staging-shm"))
        }
    }

    private class ImportedSong(val item: ContentItem, val liked: Boolean, val addedAt: Long?, val likedAt: Long?)

    private class Source(
        val songs: Map<String, ImportedSong>,
        val likedAlbums: List<ImportedSong>,
        val bookmarkedArtists: List<ImportedSong>,
        val blockedArtists: List<PortableArtistBlockRow>,
        val playlists: List<PortablePlaylistRow>,
        val entries: Map<String, List<PortablePlaylistEntry>>,
        val listens: List<Pair<PortableListenEventRow, List<PortableListenEntityRow>>>,
        val tags: List<PortableTagRow>,
        val assignments: List<PortablePlaylistTagRow>,
        val searches: List<String>,
        val setVideoIds: List<PortableSetVideoIdRow>,
        val podcastProgress: List<PortablePodcastProgressRow>,
        val songMetadata: List<PortableSongMetadataOverrideRow>,
        val droppedEntries: Int,
    ) {
        val outcome = Outcome(
            librarySongs = songs.size, likedAlbums = likedAlbums.size,
            bookmarkedArtists = bookmarkedArtists.size, blockedArtists = blockedArtists.size,
            playlists = playlists.size, playlistEntries = entries.values.sumOf { it.size },
            listenEvents = listens.size, tags = tags.size, tagAssignments = assignments.size,
            searches = searches.size, setVideoIds = setVideoIds.size, droppedEntries = droppedEntries,
            podcastProgress = podcastProgress.size,
        )
    }

    private fun read(songDb: Path): Source {
        val header = Files.newInputStream(songDb).use { it.readNBytes(16) }
        require(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) { "Not an SQLite file" }
        val connection = BundledSQLiteDriver().open(songDb.toString())
        try {
            val version = connection.prepare("PRAGMA user_version").use {
                require(it.step()) { "No schema version" }
                it.getLong(0).toInt()
            }
            require(version in 1..ANDROID_SCHEMA_VERSION) { "song.db schema $version is newer than this importer" }
            require(connection.tableExists("song")) { "song.db has no song table" }

            val artistsBySong = HashMap<String, MutableList<ContentCredit>>()
            if (connection.tableExists("song_artist_map") && connection.tableExists("artist")) {
                connection.prepare(
                    "SELECT m.songId, a.id, a.name FROM song_artist_map m " +
                        "JOIN artist a ON a.id = m.artistId ORDER BY m.songId, m.position",
                ).use { statement ->
                    while (statement.step()) {
                        val name = statement.getText(2)
                        if (name.isBlank()) continue
                        artistsBySong.getOrPut(statement.getText(0)) { ArrayList() }
                            .add(ContentCredit(name, statement.getText(1).takeIf { it.isNotBlank() }))
                    }
                }
            }

            // The baseline resolves a song's album through song_album_map when its denormalized album columns are
            // empty, so imported listens and items get the same album credit the baseline's statistics see.
            val albumsBySong = HashMap<String, Pair<String, String>>()
            if (connection.tableExists("song_album_map") && connection.tableExists("album")) {
                connection.prepare(
                    "SELECT m.songId, a.id, a.title FROM song_album_map m " +
                        "JOIN album a ON a.id = m.albumId ORDER BY m.songId, m.`index`",
                ).use { statement ->
                    while (statement.step()) {
                        albumsBySong.putIfAbsent(statement.getText(0), statement.getText(1) to statement.getText(2))
                    }
                }
            }

            val songs = HashMap<String, ImportedSong>()
            val providersBySong = HashMap<String, String>()
            val metadataBySong = LinkedHashMap<String, PortableSongMetadataOverrideRow>()
            val hasLegacyTitleFlag = connection.columnExists("song", "titleOverride")
            connection.prepare(
                "SELECT id, title, duration, thumbnailUrl, albumId, albumName, explicit, liked, likedDate, " +
                    "inLibrary, isLocal, ${if (hasLegacyTitleFlag) "titleOverride" else "0"} FROM song",
            ).use { statement ->
                while (statement.step()) {
                    val id = statement.getText(0)
                    if (id.isBlank()) continue
                    val provider = if (statement.getLong(10) != 0L) {
                        ContentProvider.LOCAL.id
                    } else {
                        ContentProvider.YOUTUBE.id
                    }
                    providersBySong[id] = provider
                    if (statement.getLong(11) != 0L) {
                        // Older Android versions replaced the title in place; the original cannot be
                        // recovered from that backup. Preserve the explicit edit until a provider refresh.
                        metadataBySong[id] = PortableSongMetadataOverrideRow(provider, id, statement.getText(1), null, 0L)
                    }
                    val credits = artistsBySong[id].orEmpty()
                    val subtitle = credits.joinToString("; ") { it.name }
                    val albumName = statement.textOrNull(5) ?: albumsBySong[id]?.second
                    val item = ContentItem(
                        ref = ContentRef(provider, ContentKind.SONG, id),
                        title = statement.getText(1),
                        subtitle = subtitle,
                        artworkUri = statement.textOrNull(3),
                        durationMs = statement.getLong(2) * 1000L,
                        explicit = statement.getLong(6) != 0L,
                        album = albumName,
                        artists = credits,
                        albumId = statement.textOrNull(4) ?: albumsBySong[id]?.first,
                    )
                    val liked = statement.getLong(7) != 0L
                    val inLibrary = statement.getLong(9).let { if (statement.isNull(9)) null else it }
                    if (inLibrary != null || liked) {
                        val likedAt = statement.getLong(8).let { if (statement.isNull(8)) null else it }
                        songs[id] = ImportedSong(item, liked, inLibrary, likedAt)
                    }
                }
            }
            if (connection.tableExists("song_metadata_override")) {
                connection.prepare("SELECT songId, title, artistNames, updatedAt FROM song_metadata_override").use { statement ->
                    while (statement.step()) {
                        val id = statement.getText(0)
                        // Source identity is the Android row's isLocal flag, never a title/artist guess.
                        val provider = providersBySong[id] ?: continue
                        val title = statement.textOrNull(1)?.takeIf(String::isNotBlank)
                        val artists = statement.textOrNull(2)?.lines()?.map(String::trim)
                            ?.filter(String::isNotEmpty)?.distinct()?.takeIf { it.isNotEmpty() }
                        if (title != null || artists != null) {
                            metadataBySong[id] = PortableSongMetadataOverrideRow(
                                provider, id, title,
                                artists?.let { JsonArray(it.map(::JsonPrimitive)).toString() },
                                statement.getLong(3),
                            )
                        }
                    }
                }
            }

            val likedAlbums = ArrayList<ImportedSong>()
            if (connection.tableExists("album")) {
                connection.prepare(
                    "SELECT id, title, thumbnailUrl, explicit, bookmarkedAt, likedDate, inLibrary, isLocal " +
                        "FROM album WHERE bookmarkedAt IS NOT NULL OR likedDate IS NOT NULL OR inLibrary IS NOT NULL",
                ).use { statement ->
                    while (statement.step()) {
                        val provider = if (statement.getLong(7) != 0L) ContentProvider.LOCAL.id else ContentProvider.YOUTUBE.id
                        val firstFlag = listOf(
                            statement.getLong(4).let { if (statement.isNull(4)) null else it },
                            statement.getLong(5).let { if (statement.isNull(5)) null else it },
                            statement.getLong(6).let { if (statement.isNull(6)) null else it },
                        ).filterNotNull().minOrNull()
                        likedAlbums += ImportedSong(
                            ContentItem(
                                ref = ContentRef(provider, ContentKind.ALBUM, statement.getText(0)),
                                title = statement.getText(1),
                                artworkUri = statement.textOrNull(2),
                                explicit = statement.getLong(3) != 0L,
                            ),
                            liked = true, addedAt = firstFlag, likedAt = firstFlag,
                        )
                    }
                }
            }

            val bookmarkedArtists = ArrayList<ImportedSong>()
            val blockedArtists = ArrayList<PortableArtistBlockRow>()
            if (connection.tableExists("artist")) {
                connection.prepare("SELECT id, name, thumbnailUrl, bookmarkedAt, blockedAt, isLocal FROM artist").use {
                    while (it.step()) {
                        val id = it.getText(0)
                        val name = it.getText(1)
                        val provider = if (it.getLong(5) != 0L) ContentProvider.LOCAL.id else ContentProvider.YOUTUBE.id
                        if (!it.isNull(3) && name.isNotBlank()) {
                            bookmarkedArtists += ImportedSong(
                                ContentItem(ContentRef(provider, ContentKind.ARTIST, id), name,
                                    artworkUri = it.textOrNull(2)),
                                liked = true, addedAt = it.getLong(3), likedAt = it.getLong(3),
                            )
                        }
                        if (!it.isNull(4) && name.isNotBlank()) {
                            blockedArtists += PortableArtistBlockRow(provider, id, name, it.getLong(4))
                        }
                    }
                }
            }

            val playlists = ArrayList<PortablePlaylistRow>()
            if (connection.tableExists("playlist")) {
                connection.prepare(
                    "SELECT id, name, createdAt, lastUpdateTime, songSortType, songSortDescending, isHidden " +
                        "FROM playlist ORDER BY COALESCE(createdAt, lastUpdateTime, 0), rowid",
                ).use { statement ->
                    var sequence = 0L
                    while (statement.step()) {
                        sequence += 1
                        val sort = statement.textOrNull(4)
                        playlists += PortablePlaylistRow(
                            id = statement.getText(0),
                            title = statement.getText(1),
                            createdSeq = sequence,
                            songSortType = sort?.takeIf { value ->
                                runCatching { PlaylistSongSortType.valueOf(value) }.isSuccess
                            } ?: "CUSTOM",
                            songSortDescending = statement.isNull(5) || statement.getLong(5) != 0L,
                            isHidden = statement.getLong(6) != 0L,
                        )
                    }
                }
            }

            val entries = HashMap<String, List<PortablePlaylistEntry>>()
            var droppedEntries = 0
            if (connection.tableExists("playlist_song_map") && connection.tableExists("song")) {
                val itemsById = HashMap<String, ContentItem>()
                connection.prepare(
                    "SELECT id, title, duration, thumbnailUrl, albumId, albumName, explicit, isLocal FROM song",
                ).use { statement ->
                    while (statement.step()) {
                        val id = statement.getText(0)
                        val credits = artistsBySong[id].orEmpty()
                        itemsById[id] = ContentItem(
                            ref = ContentRef(
                                if (statement.getLong(7) != 0L) ContentProvider.LOCAL.id else ContentProvider.YOUTUBE.id,
                                ContentKind.SONG, id,
                            ),
                            title = statement.getText(1),
                            subtitle = credits.joinToString("; ") { it.name },
                            artworkUri = statement.textOrNull(3),
                            durationMs = statement.getLong(2) * 1000L,
                            explicit = statement.getLong(6) != 0L,
                            album = statement.textOrNull(5) ?: albumsBySong[id]?.second,
                            artists = credits,
                            albumId = statement.textOrNull(4) ?: albumsBySong[id]?.first,
                        )
                    }
                }
                connection.prepare(
                    "SELECT playlistId, songId, position, setVideoId FROM playlist_song_map " +
                        "ORDER BY playlistId, position, id",
                ).use { statement ->
                    val grouped = LinkedHashMap<String, MutableList<PortablePlaylistEntry>>()
                    while (statement.step()) {
                        val playlistId = statement.getText(0)
                        val item = itemsById[statement.getText(1)]
                        if (item == null) {
                            droppedEntries += 1
                            continue
                        }
                        val list = grouped.getOrPut(playlistId) { ArrayList() }
                        val setVideoId = statement.textOrNull(3)
                        val occurrence = ContentOccurrence(
                            occurrenceId = UUID.randomUUID().toString(),
                            item = item,
                            setVideoId = setVideoId,
                        )
                        list += PortablePlaylistEntry(
                            playlistId = playlistId,
                            occurrenceId = occurrence.occurrenceId,
                            position = list.size,
                            json = ContentJson.encode(occurrence).toString(),
                            addedSeq = list.size.toLong(),
                        )
                    }
                    entries.putAll(grouped)
                }
            }

            val listens = ArrayList<Pair<PortableListenEventRow, List<PortableListenEntityRow>>>()
            if (connection.tableExists("event")) {
                val itemsById = HashMap<String, ContentItem>()
                connection.prepare(
                    "SELECT id, title, duration, thumbnailUrl, albumId, albumName, explicit, isLocal FROM song",
                ).use { statement ->
                    while (statement.step()) {
                        val id = statement.getText(0)
                        val credits = artistsBySong[id].orEmpty()
                        itemsById[id] = ContentItem(
                            ref = ContentRef(
                                if (statement.getLong(7) != 0L) ContentProvider.LOCAL.id else ContentProvider.YOUTUBE.id,
                                ContentKind.SONG, id,
                            ),
                            title = statement.getText(1),
                            subtitle = credits.joinToString("; ") { it.name },
                            artworkUri = statement.textOrNull(3),
                            durationMs = statement.getLong(2) * 1000L,
                            explicit = statement.getLong(6) != 0L,
                            album = statement.textOrNull(5) ?: albumsBySong[id]?.second,
                            artists = credits,
                            albumId = statement.textOrNull(4) ?: albumsBySong[id]?.first,
                        )
                    }
                }
                connection.prepare("SELECT id, songId, timestamp, playTime FROM event ORDER BY id").use { statement ->
                    while (statement.step()) {
                        val eventId = statement.getLong(0)
                        val item = itemsById[statement.getText(1)] ?: continue
                        val id = "legacy-event-$eventId"
                        val occurrence = ContentOccurrence(UUID.randomUUID().toString(), item)
                        listens += PortableListenEventRow(
                            id = id,
                            occurrenceJson = ContentJson.encode(occurrence).toString(),
                            songKey = PlaylistSongOrder.playKey(item.ref),
                            artistName = item.subtitle,
                            albumName = item.album,
                            timestampMs = statement.getLong(2),
                            playTimeMs = statement.getLong(3),
                        ) to listenEntities(id, occurrence)
                    }
                }
            }

            val tags = ArrayList<PortableTagRow>()
            val assignments = ArrayList<PortablePlaylistTagRow>()
            if (connection.tableExists("tag")) {
                connection.prepare("SELECT id, name, color FROM tag ORDER BY rowid").use { statement ->
                    while (statement.step()) {
                        tags += PortableTagRow(statement.getText(0), statement.getText(1), statement.getText(2))
                    }
                }
            }
            if (connection.tableExists("playlist_tag_map")) {
                val playlistIds = playlists.mapTo(HashSet()) { it.id }
                val tagIds = tags.mapTo(HashSet()) { it.id }
                connection.prepare("SELECT playlistId, tagId FROM playlist_tag_map").use { statement ->
                    while (statement.step()) {
                        val playlistId = statement.getText(0)
                        val tagId = statement.getText(1)
                        if (playlistId in playlistIds && tagId in tagIds) {
                            assignments += PortablePlaylistTagRow(playlistId, tagId)
                        }
                    }
                }
            }

            val searches = ArrayList<String>()
            if (connection.tableExists("search_history")) {
                connection.prepare("SELECT `query` FROM search_history ORDER BY id").use { statement ->
                    while (statement.step()) searches += statement.getText(0)
                }
            }

            val setVideoIds = ArrayList<PortableSetVideoIdRow>()
            if (connection.tableExists("set_video_id")) {
                connection.prepare("SELECT videoId, setVideoId FROM set_video_id").use { statement ->
                    while (statement.step()) {
                        setVideoIds += PortableSetVideoIdRow(
                            statement.getText(0),
                            statement.textOrNull(1),
                        )
                    }
                }
            }

            val progress = ArrayList<PortablePodcastProgressRow>()
            if (connection.tableExists("podcast_progress")) {
                connection.prepare(
                    "SELECT episodeId, positionMs, durationMs, finished, updatedAtMs FROM podcast_progress",
                ).use { statement ->
                    while (statement.step()) {
                        progress += PortablePodcastProgressRow(
                            episodeId = statement.getText(0),
                            positionMs = statement.getLong(1),
                            durationMs = if (statement.isNull(2)) null else statement.getLong(2),
                            finished = statement.getLong(3) != 0L,
                            updatedAtMs = statement.getLong(4),
                        )
                    }
                }
            }

            return Source(songs, likedAlbums, bookmarkedArtists, blockedArtists, playlists, entries, listens,
                tags, assignments, searches, setVideoIds, progress, metadataBySong.values.toList(), droppedEntries)
        } finally {
            connection.close()
        }
    }

    private suspend fun write(database: PortableStateDatabase, source: Source) {
        val library = database.library()
        for (song in source.songs.values) {
            library.putItem(
                libraryItemRow(
                    song.item, liked = song.liked,
                    addedSeq = song.addedAt ?: Long.MAX_VALUE,
                    likedSeq = if (song.liked) song.likedAt ?: Long.MAX_VALUE else 0L,
                ),
            )
        }
        for (album in source.likedAlbums) {
            library.putItem(
                libraryItemRow(album.item, liked = true,
                    addedSeq = album.addedAt ?: Long.MAX_VALUE, likedSeq = album.likedAt ?: Long.MAX_VALUE),
            )
        }
        for (artist in source.bookmarkedArtists) {
            library.putItem(
                libraryItemRow(artist.item, liked = true,
                    addedSeq = artist.addedAt ?: Long.MAX_VALUE, likedSeq = artist.likedAt ?: Long.MAX_VALUE),
            )
        }
        val blocks = database.artistBlocks()
        for (block in source.blockedArtists) blocks.insertIfAbsent(block)
        for (tag in source.tags) {
            runCatching { library.insertTag(tag) }
        }
        for (playlist in source.playlists) {
            runCatching { library.insertPlaylist(playlist) }
            val entries = source.entries[playlist.id].orEmpty()
            if (entries.isNotEmpty()) {
                runCatching { library.putEntries(entries) }
            }
        }
        val assignmentPlaylists = source.playlists.mapTo(HashSet()) { it.id }
        library.putTagAssignments(source.assignments.filter { it.playlistId in assignmentPlaylists })
        val listens = database.listens()
        for ((event, entities) in source.listens) listens.insertListen(event, entities)
        val search = database.searchHistory()
        for (query in source.searches) {
            runCatching { search.put(PortableSearchHistoryRow(query = query)) }
        }
        val setVideoIds = database.setVideoIds()
        for (row in source.setVideoIds) setVideoIds.put(row)
        val progress = database.podcastProgress()
        for (row in source.podcastProgress) progress.put(row)
        for (row in source.songMetadata) database.songMetadataOverrides().put(row)
    }

    private fun SQLiteConnection.tableExists(name: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
            it.bindText(1, name)
            it.step()
        }

    private fun SQLiteConnection.columnExists(table: String, column: String): Boolean =
        prepare("PRAGMA table_info(`$table`)").use {
            while (it.step()) if (it.getText(1) == column) return true
            false
        }

    private fun SQLiteConnection.execSql(sql: String) {
        prepare(sql).use { it.step() }
    }
}


    private fun androidx.sqlite.SQLiteStatement.textOrNull(index: Int): String? =
        if (isNull(index)) null else getText(index)
