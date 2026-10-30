package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.ArtistSortType
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibraryAlbumRow
import moe.rukamori.archivetune.shared.domain.library.LibraryArtistRow
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.SongSortType
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistSongOrder
import moe.rukamori.archivetune.shared.domain.playlist.StoredPlaylistRow
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository
import moe.rukamori.archivetune.shared.domain.songmetadata.matchesSongSearch
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverrides

/**
 * Local portable library only. Remote playlist adapters must commit remote removal before local removal. [listens] is
 * the listening store that PLAY_TIME order reads. Without it that order emits no list, as before the store existed.
 * [storedSongs] is the index of downloads and cache entries. Without it the downloaded and cache lists emit no list.
 * [albumTracks] holds the complete album track lists that the full-album list reads. Without it that list emits none.
 */
class RoomLibraryRepository(
    private val dao: PortableLibraryDao,
    private val pageSize: Int = 50,
    private val listens: ListeningEventDao? = null,
    private val storedSongs: PortableStoredSongDao? = null,
    private val albumTracks: PortableAlbumTrackDao? = null,
    val songMetadata: SongMetadataRepository? = null,
) : PlaylistImportLibrary {
    init { require(pageSize in 1..1000) }
    suspend fun saveItems(items: List<ContentItem>) = dao.saveItems(items)
    private fun metadataEdits(): Flow<Map<ContentRef, SongMetadataOverride>> =
        songMetadata?.observeOverrides() ?: flowOf(emptyMap())

    private suspend fun effective(item: ContentItem): ContentItem =
        item.withSongMetadataOverride(songMetadata?.overrides()?.get(item.ref))

    private fun effectivePages(pages: Flow<ContentPage>): Flow<ContentPage> =
        combine(pages, metadataEdits()) { page, edits -> page.withSongMetadataOverrides(edits) }
    /** Deletes the local playlist [id], its songs list and its tags. An unknown id throws and changes nothing. */
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)
    suspend fun createPlaylist(id: String, title: String) {
        require(id.isNotBlank() && title.isNotBlank())
        dao.createPlaylist(PortablePlaylistRow(id, title))
    }
    /** The local playlists the Android app hid from its Library, by title. */
    fun observeHiddenPlaylists(): Flow<List<PortablePlaylistRow>> = dao.observeHiddenPlaylists()

    /** Sets one playlist's hidden flag. An unknown id throws and changes nothing. */
    suspend fun setPlaylistHidden(id: String, hidden: Boolean) {
        require(dao.setPlaylistHidden(id, hidden) == 1) { "Unknown local playlist" }
    }

    suspend fun playlistTags(id: String): Set<String> {
        requireNotNull(dao.playlist(id)) { "Unknown local playlist" }
        return dao.playlistTagNames(id).toSet()
    }

    /** The playlist's own song order, read from its row. No playlist shares another playlist's order. */
    suspend fun playlistSongSort(id: String): LibrarySortState<PlaylistSongSortType> =
        requireNotNull(dao.playlist(id)) { "Unknown local playlist" }.songSort()

    /** Writes both halves of one playlist's song order in one edit. Other playlists keep their own order. */
    suspend fun setPlaylistSongSort(id: String, sort: LibrarySortState<PlaylistSongSortType>) =
        dao.setSongSort(id, sort.type.name, sort.descending)

    /**
     * The playlist's own song order as its row stores it, emitted again when the row changes. Null when the playlist is
     * gone, so a caller never reads another playlist's order in its place.
     */
    fun observePlaylistSongSort(id: String): Flow<LibrarySortState<PlaylistSongSortType>?> =
        dao.observePlaylistRow(id).map { rows -> rows.firstOrNull()?.songSort() }

    /**
     * Every local playlist with the values its screens read, in the owner's playlist order (by title), emitted again when
     * a playlist or its songs change. Songs of blocked artists are counted here: they stay stored, and only a playlist's
     * own list leaves them out. [LocalPlaylistSummary.newestAddedSeq] is the latest song addition, the only
     * change a stored playlist records. Renames and custom moves leave no sequence of their own.
     */
    fun observeLocalPlaylists(): Flow<List<LocalPlaylistSummary>> = dao.observePlaylists("").flatMapLatest { rows ->
        if (rows.isEmpty()) {
            flowOf(emptyList<LocalPlaylistSummary>())
        } else {
            combine(rows.map { row ->
                dao.observeEntries(row.id).map { entries ->
                    LocalPlaylistSummary(
                        occurrence = playlistOccurrence(row),
                        songCount = entries.size,
                        createdSeq = row.createdSeq ?: 0L,
                        newestAddedSeq = entries.maxOfOrNull { it.addedSeq } ?: 0L,
                    )
                }
            }) { summaries -> summaries.toList() }
        }
    }

    /**
     * The complete playlist in its stored song order, emitted again when the playlist, its sort, the listens or the
     * blocked artists change. It is never a page. Songs by a blocked artist are left out, as the baseline's playlist
     * screen left them out, and stay stored. A sort that needs data this store lacks (PLAY_TIME) emits a null page,
     * so the caller shows no list rather than another order. [collation] orders artist text for ARTIST, as the device
     * collator does.
     */
    fun observeSortedPlaylist(
        playlistId: String,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> =
        combine(
            dao.observePlaylistRow(playlistId),
            dao.observeEntries(playlistId),
            playTimeTotals(),
            blocked,
            metadataEdits(),
        ) { rows, entries, totals, blockedSet, edits ->
            val row = rows.firstOrNull()
            if (row == null) {
                ContentPage(emptyList())
            } else {
                val stored = entries
                    .map { StoredPlaylistRow(decode(it.json).withSongMetadataOverrides(edits), it.position, it.addedSeq) }
                    .filterNot { it.occurrence.item.hasBlockedArtist(blockedSet) }
                PlaylistSongOrder.ordered(stored, row.songSort(), collation, totals)
                    ?.let { ContentPage(it.map(StoredPlaylistRow::occurrence)) }
            }
        }

    /** All-time play time per song, re-emitted as listens are added. Null when no listening store is wired. */
    private fun playTimeTotals(): Flow<Map<String, Long>?> =
        listens?.observeSongPlayTotals()?.map { totals -> totals.associate { it.songKey to it.totalMs } }
            ?: flowOf(null)

    /**
     * The complete song library in [sort]'s order, emitted again when the library, the listens or the blocked artists
     * change. Songs by a blocked artist are left out, as the baseline's library lists left them out. CREATE_DATE is
     * when each song entered the library. A PLAY_TIME order needs the listening store and emits a null page without it.
     */
    fun observeLibrarySongs(
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> = observeSongs(liked = false, sort = sort, collation = collation, blocked = blocked)

    /**
     * The liked songs in [sort]'s order, which is the baseline's auto-playlist of likes. CREATE_DATE is when each song
     * was last liked. Blocked artists' songs are left out, exactly as in [observeLibrarySongs].
     */
    fun observeLikedSongs(
        sort: LibrarySortState<AutoPlaylistSongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> = observeSongs(
        liked = true,
        sort = LibrarySortState(SongSortType.valueOf(sort.type.name), sort.descending),
        collation = collation,
        blocked = blocked,
    )

    /**
     * The complete album list of the saved library in [sort]'s order, emitted again when the library, the listens or
     * the blocked artists change. Only an album with a provider album id is listed, and a song without one is in no
     * album. An album is hidden when a saved song of it credits a blocked artist. YEAR emits a null list because no
     * release year is stored, and PLAY_TIME emits one without the listening store, so neither shows another order.
     */
    fun observeLibraryAlbums(
        sort: LibrarySortState<AlbumSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryAlbumRow>?> =
        combine(dao.observeSongRows(false), playTimeTotals(), blocked) { rows, totals, blockedSet ->
            libraryAlbumRows(rows.map { savedSong(it) }, totals, blockedSet, sort, collation)
        }

    /**
     * The complete artist list of the saved library in [sort]'s order, emitted again when the library, the listens or
     * the blocked artists change. Only YouTube and local artists are listed, as the baseline listed them. A blocked
     * artist is hidden, while a saved song still counts toward the other artists it credits.
     */
    fun observeLibraryArtists(
        sort: LibrarySortState<ArtistSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryArtistRow>?> =
        combine(dao.observeSongRows(false), playTimeTotals(), blocked) { rows, totals, blockedSet ->
            libraryArtistRows(rows.map { savedSong(it) }, totals, blockedSet, sort, collation)
        }

    /**
     * The liked albums in [sort]'s order, emitted again when the likes, the saved songs, the listens or the blocked
     * artists change. A liked album is listed without any saved song, as the baseline's liked albums were. CREATE_DATE
     * is when each album was liked. An album is hidden when the credits its provider gave name a blocked artist. YEAR
     * emits a null list because no release year is stored, and PLAY_TIME emits one without the listening store.
     */
    fun observeLikedAlbums(
        sort: LibrarySortState<AlbumSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryAlbumRow>?> = combine(
        dao.observeBookmarks(ContentKind.ALBUM.name),
        dao.observeSongRows(false),
        playTimeTotals(),
        blocked,
    ) { bookmarks, saved, totals, blockedSet ->
        likedAlbumRows(bookmarks.map(::bookmarkSong), saved.map(::savedSong), totals, blockedSet, sort, collation)
    }

    /**
     * The liked artists in [sort]'s order, emitted again when the likes, the saved songs, the listens or the blocked
     * artists change. Only YouTube and local artists are listed, and a blocked artist is hidden, as the baseline lists
     * them. CREATE_DATE is when each artist was liked. PLAY_TIME emits a null list without the listening store.
     */
    fun observeLikedArtists(
        sort: LibrarySortState<ArtistSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryArtistRow>?> = combine(
        dao.observeBookmarks(ContentKind.ARTIST.name),
        dao.observeSongRows(false),
        playTimeTotals(),
        blocked,
    ) { bookmarks, saved, totals, blockedSet ->
        likedArtistRows(bookmarks.map(::bookmarkSong), saved.map(::savedSong), totals, blockedSet, sort, collation)
    }

    /**
     * The songs this device keeps as downloads, in [sort]'s order, emitted again when the downloads, the saved songs,
     * the listens or the blocked artists change. CREATE_DATE is when each download completed. Songs by a blocked artist
     * are left out. Without the stored-song index the page is null, so the caller shows no list.
     */
    fun observeDownloadedSongs(
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> = observeStoredSongs(StoredSongOrigin.DOWNLOAD, sort, collation, blocked)

    /**
     * The complete cache list in [sort]'s order, which is the baseline's cached playlist: songs the player kept in its
     * stream cache, minus any song this device has downloaded. CREATE_DATE is when each entry first became complete,
     * which the baseline stamped as the download date. A platform producer fills the cache, so the list is empty until
     * one records entries. Without the stored-song index the page is null.
     */
    fun observeCacheSongs(
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> = observeStoredSongs(StoredSongOrigin.CACHE, sort, collation, blocked)

    /**
     * The albums of the downloaded songs in [sort]'s order, emitted again as the downloads, the saved songs, the
     * listens or the blocked artists change. A download is in an album only with a provider album id. Its song count,
     * length and play time count the downloaded songs, since the library stores no full track list. YEAR emits a null
     * list, as the saved-album list does. Without the stored-song index the list is null.
     */
    fun observeDownloadedAlbums(
        sort: LibrarySortState<AlbumSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryAlbumRow>?> {
        val index = storedSongs ?: return flowOf(null)
        return combine(
            index.observeListed(StoredSongOrigin.DOWNLOAD),
            dao.observeSongRows(false),
            playTimeTotals(),
            blocked,
        ) { stored, saved, totals, blockedSet ->
            val artwork = artworkOf(saved)
            val songs = stored.map { row -> SavedSong(decode(row.json).item.withArtwork(artwork), row.storedAtMs) }
            libraryAlbumRows(songs, totals, blockedSet, sort, collation)
        }
    }

    /**
     * The albums of the downloaded songs that are known to be full, in [sort]'s order. An album is full when its
     * complete track list was recorded from a whole walk and every track of it is downloaded. An album whose list is
     * unknown, or only partly downloaded, is left out. Its song count, length and play time count its downloads, as
     * [observeDownloadedAlbums] does. It is emitted again as the downloads, the recorded lists, the saved songs, the
     * listens or the blocked artists change. YEAR emits a null list, and so does a missing index or album list.
     */
    fun observeDownloadedFullAlbums(
        sort: LibrarySortState<AlbumSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<List<LibraryAlbumRow>?> {
        val index = storedSongs ?: return flowOf(null)
        val recorded = albumTracks ?: return flowOf(null)
        val lists = combine(recorded.observeComplete(), recorded.observeTracks()) { albums, tracks ->
            albums to tracks
        }
        return combine(
            index.observeListed(StoredSongOrigin.DOWNLOAD),
            lists,
            dao.observeSongRows(false),
            playTimeTotals(),
            blocked,
        ) { stored, (albums, tracks), saved, totals, blockedSet ->
            val artwork = artworkOf(saved)
            val songs = stored.map { row -> SavedSong(decode(row.json).item.withArtwork(artwork), row.storedAtMs) }
            val full = fullyDownloadedAlbums(stored.mapTo(HashSet()) { storedRef(it) }, albums, tracks)
            libraryAlbumRows(songs, totals, blockedSet, sort, collation)?.filter { it.album.ref in full }
        }
    }

    /**
     * The stored songs of [origin] as a complete list, in [sort]'s order. A cache entry is left out while the song is
     * also downloaded, as the baseline's cache list left it out. Songs by a blocked artist are left out too.
     */
    private fun observeStoredSongs(
        origin: String,
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> {
        val index = storedSongs ?: return flowOf(null)
        val downloads: Flow<List<PortableStoredSongRow>> = if (origin == StoredSongOrigin.CACHE) {
            index.observeListed(StoredSongOrigin.DOWNLOAD)
        } else {
            flowOf(emptyList<PortableStoredSongRow>())
        }
        val blockedAndEdits = combine(blocked, metadataEdits()) { blocks, edits -> blocks to edits }
        return combine(
            index.observeListed(origin),
            downloads,
            dao.observeSongRows(false),
            playTimeTotals(),
            blockedAndEdits,
        ) { stored, downloaded, saved, totals, (blockedSet, edits) ->
            val downloadedSongs = downloaded.mapTo(HashSet()) { storedRef(it) }
            val artwork = artworkOf(saved)
            val songs = stored
                .filterNot { origin == StoredSongOrigin.CACHE && storedRef(it) in downloadedSongs }
                .mapNotNull { row ->
                    val occurrence = decode(row.json).let { it.copy(item = it.item.withArtwork(artwork)) }
                        .withSongMetadataOverrides(edits)
                    if (occurrence.item.hasBlockedArtist(blockedSet)) null else LibrarySong(occurrence, row.storedAtMs)
                }
            orderedSongs(songs, sort, collation, totals)?.let { ContentPage(it.map(LibrarySong::occurrence)) }
        }
    }

    private fun storedRef(row: PortableStoredSongRow): ContentRef =
        ContentRef(row.provider, ContentKind.SONG, row.mediaId)

    /**
     * The stored rows of [origin] that a list may show. A download with no committed byte count has no size to check
     * its file against, so no list shows it until startup reconciliation adopts its earlier file or drops the row.
     */
    private fun PortableStoredSongDao.observeListed(origin: String): Flow<List<PortableStoredSongRow>> =
        observe(origin).map { rows ->
            if (origin == StoredSongOrigin.DOWNLOAD) rows.filter { it.bytes != null } else rows
        }

    /** The artwork address of each saved song. A stored entry keeps none, so the saved copy of the song supplies it. */
    private fun artworkOf(saved: List<LibraryItemRow>): Map<ContentRef, String> = saved.mapNotNull { row ->
        decode(row.json).item.let { item -> item.artworkUri?.let { item.ref to it } }
    }.toMap()

    private fun ContentItem.withArtwork(artwork: Map<ContentRef, String>): ContentItem =
        if (artworkUri != null) this else copy(artworkUri = artwork[ref])

    private fun savedSong(row: LibraryItemRow): SavedSong = SavedSong(decode(row.json).item, row.addedSeq)

    /** A liked album or artist, with the sequence its liked list orders by: when it was liked. */
    private fun bookmarkSong(row: LibraryItemRow): SavedSong = SavedSong(decode(row.json).item, row.likedSeq)

    private fun observeSongs(
        liked: Boolean,
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        blocked: Flow<Set<ContentRef>>,
    ): Flow<ContentPage?> =
        combine(dao.observeSongRows(liked), playTimeTotals(), blocked, metadataEdits()) { rows, totals, blockedSet, edits ->
            val songs = rows.mapNotNull { row ->
                val occurrence = decode(row.json).withSongMetadataOverrides(edits)
                if (occurrence.item.hasBlockedArtist(blockedSet)) {
                    null
                } else {
                    LibrarySong(occurrence, if (liked) row.likedSeq else row.addedSeq)
                }
            }
            orderedSongs(songs, sort, collation, totals)?.let { ContentPage(it.map(LibrarySong::occurrence)) }
        }

    /** A saved song with the sequence its sort reads: when it entered the library, or when it was last liked. */
    private class LibrarySong(val occurrence: ContentOccurrence, val sequence: Long)

    /**
     * The songs in [sort]'s order. A descending order is the reverse of the whole ascending list, as the playlist
     * orders are. PLAY_TIME without [totals] returns null, so no other order is shown in its place.
     */
    private fun orderedSongs(
        songs: List<LibrarySong>,
        sort: LibrarySortState<SongSortType>,
        collation: Comparator<String>,
        totals: Map<String, Long>?,
    ): List<LibrarySong>? {
        val ascending = when (sort.type) {
            SongSortType.CREATE_DATE -> songs.sortedBy { it.sequence }
            SongSortType.NAME -> songs.sortedWith(Comparator<LibrarySong> { left, right ->
                collation.compare(left.occurrence.item.title, right.occurrence.item.title)
            })
            SongSortType.ARTIST -> songs.sortedWith(Comparator<LibrarySong> { left, right ->
                collation.compare(artistText(left.occurrence.item), artistText(right.occurrence.item))
            })
            SongSortType.PLAY_TIME -> {
                val played = totals ?: return null
                songs.sortedBy { played[PlaylistSongOrder.playKey(it.occurrence.item.ref)] ?: 0L }
            }
        }
        return if (sort.descending) ascending.reversed() else ascending
    }

    /** The artist text a song sorts by: the names of its credits when the provider gave them, else its subtitle. */
    private fun artistText(item: ContentItem): String =
        item.artists.joinToString(", ") { it.name }.ifEmpty { item.subtitle }

    private fun PortablePlaylistRow.songSort(): LibrarySortState<PlaylistSongSortType> =
        LibrarySortState(songSortType.toEnum(PlaylistSongSortType.CUSTOM), songSortDescending)

    override suspend fun findSong(mediaId: String, provider: String?): ContentItem? =
        dao.songByMediaId(mediaId, provider)?.let { effective(decode(it.json).item) }

    override suspend fun createImportedPlaylist(id: String, title: String, occurrences: List<ContentOccurrence>, tags: Set<String>) {
        require(id.isNotBlank() && title.isNotBlank())
        dao.createImportedPlaylist(PortablePlaylistRow(id, title), occurrences, tags)
    }

    override suspend fun appendImported(playlistId: String, occurrences: List<ContentOccurrence>, tags: Set<String>) =
        dao.appendImported(playlistId, occurrences, tags)

    override suspend fun likeAll(items: List<ContentItem>) = dao.likeAll(items)

    override suspend fun mutate(mutation: LibraryMutation) = dao.mutate(mutation)
    override suspend fun item(ref: ContentRef): ContentItem =
        if (ref.provider == "local" && ref.kind == ContentKind.PLAYLIST)
            playlistOccurrence(requireNotNull(dao.playlist(ref.id))).item
        else effective(decode(requireNotNull(dao.item(ref.provider, ref.kind.name, ref.id)).json).item)

    override suspend fun page(query: ContentQuery, continuation: String?): ContentPage {
        val identity = queryIdentity(query)
        val offset = if (continuation == null) 0 else {
            require(continuation.startsWith("local:$identity:")) { "Continuation does not belong to this query" }
            requireNotNull(continuation.substringAfterLast(':').toIntOrNull()).also { require(it >= 0 && it <= 1_000_000) }
        }
        val entries = when(query.surface) {
            SONGS, LIKED -> searchSongs(
                dao.songRows(query.surface == LIKED), query.text.orEmpty(), songMetadata?.overrides().orEmpty(),
            ).drop(offset).take(pageSize + 1)
            PLAYLISTS -> dao.playlists(query.text.orEmpty(), pageSize + 1, offset).map(::playlistOccurrence)
            PLAYLIST_ITEMS -> {
                val edits = songMetadata?.overrides().orEmpty()
                dao.entries(playlistId(query), pageSize + 1, offset).map { decode(it.json).withSongMetadataOverrides(edits) }
            }
            else -> throw IllegalArgumentException("Unsupported local library surface")
        }
        return ContentPage(entries.take(pageSize),
            if (entries.size > pageSize) "local:$identity:${offset + pageSize}" else null)
    }

    override fun observe(query: ContentQuery): Flow<ContentPage> = when(query.surface) {
        SONGS, LIKED -> combine(dao.observeSongRows(query.surface == LIKED), metadataEdits()) { rows, edits ->
            ContentPage(searchSongs(rows, query.text.orEmpty(), edits))
        }
        PLAYLISTS -> dao.observePlaylists(query.text.orEmpty()).map { rows -> ContentPage(rows.map(::playlistOccurrence)) }
        PLAYLIST_ITEMS -> effectivePages(dao.observeEntries(playlistId(query)).map { rows -> ContentPage(rows.map { decode(it.json) }) })
        else -> throw IllegalArgumentException("Unsupported local library surface")
    }

    private fun searchSongs(
        rows: List<LibraryItemRow>,
        query: String,
        edits: Map<ContentRef, SongMetadataOverride>,
    ): List<ContentOccurrence> = rows.map { decode(it.json).withSongMetadataOverrides(edits) }
        .filter { it.item.matchesSongSearch(query) }
        .sortedWith(compareBy({ it.item.title }, { it.item.ref.provider }, { it.item.ref.id }))

    private fun playlistId(query: ContentQuery): String {
        require(query.text.isNullOrEmpty()) { "Playlist entry text search is not supported" }
        val parent = requireNotNull(query.parent)
        require(parent.provider == "local" && parent.kind == ContentKind.PLAYLIST)
        return parent.id
    }
    private fun queryIdentity(query: ContentQuery): String = ContentDigest.sha256(
        listOf(query.surface, query.text.orEmpty(), query.parent?.provider.orEmpty(),
            query.parent?.kind?.name.orEmpty(), query.parent?.id.orEmpty()).joinToString("") { "${it.length}:$it" }.encodeToByteArray())
        .take(16)
    private fun decode(raw: String): ContentOccurrence = ContentJson.decode(Json.parseToJsonElement(raw).jsonObject)
    private fun playlistOccurrence(row: PortablePlaylistRow): ContentOccurrence =
        ContentOccurrence("playlist:${row.id}", ContentItem(ContentRef("local", ContentKind.PLAYLIST, row.id), row.title))

    companion object {
        const val SONGS = "library_songs"
        const val LIKED = "library_liked"
        const val PLAYLISTS = "library_playlists"
        const val PLAYLIST_ITEMS = "library_playlist_items"
    }
}

/**
 * One local playlist as its screens read it. [createdSeq] is the sequence it was created in and [newestAddedSeq] the
 * newest song-addition sequence in it. Both only order rows. Neither is a clock time, and no row displays them.
 */
data class LocalPlaylistSummary(
    val occurrence: ContentOccurrence,
    val songCount: Int,
    val createdSeq: Long,
    val newestAddedSeq: Long,
)
