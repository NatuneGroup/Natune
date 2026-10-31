package moe.rukamori.archivetune.shared.ui.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.data.persistence.LocalPlaylistSummary
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.library.AlbumFilter
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.ArtistFilter
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibraryAlbumRow
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.PlaylistSortType
import moe.rukamori.archivetune.shared.domain.library.SongFilter
import moe.rukamori.archivetune.shared.domain.library.SongSortType
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.library.writeTo
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.repository.MyTopPeriod
import moe.rukamori.archivetune.shared.domain.repository.StatisticsRepository
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist
import moe.rukamori.archivetune.shared.domain.settings.AlbumFilterKey
import moe.rukamori.archivetune.shared.domain.settings.AlbumSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.AlbumSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistFilterKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.AutoPlaylistSongSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.AutoPlaylistSongSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistEditLockKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SongFilterKey
import moe.rukamori.archivetune.shared.domain.settings.SongSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.SongSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionRow
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * The one host owner of the Library lists and the playlist order controls. Every list is the owner's complete,
 * sorted, block-aware flow, read with the sort keys of the one settings owner, so a page never sorts the rows it
 * already holds. A surface whose owner cannot answer on this host is unavailable, never an empty success.
 */
class LibraryContentOwner(
    private val settings: VaultSettingsStore,
    private val library: RoomLibraryRepository,
    /**
     * The one writer of library changes: [library] wrapped so that a YouTube change settles with the provider before
     * the local commit. Every like takes this route. Reads stay on [library].
     */
    private val writes: PlaylistImportLibrary,
    private val statistics: StatisticsRepository,
    private val blocked: Flow<Set<ContentRef>>,
    private val textOrder: Comparator<String>,
    /** Wall-clock milliseconds, for the My Top windows. */
    private val nowMs: () -> Long,
    /** The local time zone offset at an instant, for the My Top day boundaries. */
    private val offsetMsAt: (epochMs: Long) -> Long,
    /** Writes one sanitized diagnostic line. */
    private val report: (String) -> Unit,
    /** Told about every like this owner wrote, after the write succeeded. It must not suspend for long. */
    private val onLikeWritten: suspend (ContentRef, Boolean) -> Unit = { _, _ -> },
) {
    /** Used by local history playback's query filter; display edits never alter the stored listen. */
    suspend fun songMetadataOverrides() = library.songMetadata?.overrides()

    /**
     * One Library section, re-read whenever its sort, its filter or the playlist order changes. An unavailable section
     * emits a failed state, so the screen never shows a list it could not produce.
     */
    fun chipPage(filter: LibraryFilter): Flow<PagedContentState> = channelFlow<PagedContentState> {
        settings.snapshots.filterNotNull().collectLatest { snapshot ->
            val chip = chipList(filter, snapshot)
            if (chip == null) {
                send(PagedContentState(failed = true))
            } else {
                val (request, pages) = chip
                pages.collect { page -> send(page.toPagedState(request)) }
            }
        }
    }

    /**
     * The complete list a playback command reads once, in the order the screen shows it. Null when the owner cannot
     * answer for [request], so playback refuses instead of guessing a list.
     */
    suspend fun currentList(request: ContentRequest): ContentPage? {
        if (request.surface == ContentSurface.STATISTICS) return topList(request)
        val snapshot = settings.snapshots.filterNotNull().first()
        return listFlow(request, snapshot)?.first()
    }

    /** The request of one My Top list. Its period and size travel in the request, so the page and playback read one list. */
    fun topRequest(period: MyTopPeriod, size: Int): ContentRequest = ContentRequest(
        provider = ContentProvider.LOCAL,
        surface = ContentSurface.STATISTICS,
        section = ContentSection(TOP_SECTION_ID, "${period.name}:$size"),
    )

    /**
     * One My Top list as its page shows it. The owner's own ranking gives the order, and the blocked artists of the
     * shared block set leave their songs out. A load the owner cannot answer is a failed page, never an empty success.
     */
    fun topPage(request: ContentRequest): Flow<PagedContentState> = flow {
        emit(PagedContentState(request = request, loading = true))
        emit(topList(request).toPagedState(request))
    }

    /**
     * The local playlists as the Playlists page lists them. The page orders and filters its own rows, so the stored
     * sequences, counts and the hidden flag are all it needs. They are order keys, never shown as dates. A hidden
     * playlist is included with [PlaylistCollectionRow.hidden] set, exactly like Android's list, so the page's own
     * Show hidden control decides whether it is drawn instead of the owner dropping it.
     */
    fun playlistRows(): Flow<List<PlaylistCollectionRow>> = library.observeLocalPlaylists().map { summaries ->
        summaries.map { summary ->
            PlaylistCollectionRow(
                item = summary.occurrence.item,
                songCount = summary.songCount,
                createdEpochMs = summary.createdSeq,
                updatedEpochMs = summary.newestAddedSeq,
                hidden = summary.hidden,
            )
        }
    }

    /**
     * One local playlist: its header, its complete page in its own stored order, that order and the shared playlist
     * lock. The header is null when the library holds no playlist under [playlistId].
     */
    fun localPlaylist(playlistId: String): Flow<LocalPlaylistView?> {
        val ref = ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, playlistId)
        val header = library.observeLocalPlaylists().map { summaries ->
            summaries.firstOrNull { it.occurrence.item.ref == ref }?.occurrence?.item
        }
        // A playlist that is gone keeps the default order, and no other playlist's stored order is ever read in its place.
        val order = library.observePlaylistSongSort(playlistId).map { sort ->
            sort ?: LibrarySortState(PlaylistSongSortType.CUSTOM, descending = true)
        }
        val locked = settings.snapshots.filterNotNull()
            .map { snapshot -> snapshot[PlaylistEditLockKey] ?: true }
            .distinctUntilChanged()
        return combine(header, library.observeSortedPlaylist(playlistId, textOrder, blocked), order, locked) {
                item, page, sort, isLocked ->
            LocalPlaylistView(item, page, sort, isLocked)
        }
    }

    /** The liked songs in the auto-playlist order, re-read when that order changes. */
    fun likedSongs(): Flow<AutoPlaylistView?> = channelFlow<AutoPlaylistView?> {
        settings.snapshots.filterNotNull().collectLatest { snapshot ->
            val sort = autoSort(snapshot)
            likedSongsWith(sort).collect { page -> send(AutoPlaylistView(page, sort)) }
        }
    }

    /**
     * Whether [ref] is one of the liked songs, read from the owner's complete liked list in the auto-playlist order. A
     * like written anywhere on this host shows here at once. A list that is not read yet reads as not liked.
     */
    fun isLiked(ref: ContentRef): Flow<Boolean> = channelFlow<Boolean> {
        settings.snapshots.filterNotNull().collectLatest { snapshot ->
            likedSongsWith(autoSort(snapshot)).collect { page -> send(page?.items?.any { it.item.ref == ref } == true) }
        }
    }.distinctUntilChanged()

    /**
     * Writes one like through the settling writer. A failed or refused write throws, and the caller reports it. A song
     * the library does not hold yet, such as one played straight from a provider, is saved and liked in one write, as the
     * baseline saves the playing song before it likes it.
     */
    suspend fun setLiked(item: ContentItem, liked: Boolean) {
        val ref = item.ref
        val unsaved = liked && ref.kind == ContentKind.SONG && writes.findSong(ref.id, ref.provider) == null
        if (unsaved) writes.likeAll(listOf(item)) else writes.mutate(LibraryMutation.Like(ref, liked))
        onLikeWritten(ref, liked)
    }

    /** Writes one playlist's own song order. Other playlists keep theirs. */
    suspend fun setPlaylistSort(playlistId: String, sort: LibrarySortState<PlaylistSongSortType>) {
        library.setPlaylistSongSort(playlistId, sort)
    }

    /** Writes the order of the Playlists list, which the Library playlists section reads as well. */
    suspend fun setPlaylistListSort(sort: LibrarySortState<PlaylistSortType>) {
        settings.edit { sort.writeTo(this, PlaylistSortTypeKey, PlaylistSortDescendingKey) }
    }

    /** Writes the playlist order lock that every editable playlist reads. */
    suspend fun setPlaylistLocked(locked: Boolean) {
        settings.edit { set(PlaylistEditLockKey, locked) }
    }

    /**
     * Saves a new local playlist under [id]. The repository refuses a blank id or title, and an id that already exists
     * fails the insert, so a failed save throws to the caller and never replaces another playlist.
     */
    suspend fun createPlaylist(id: String, title: String) {
        library.createPlaylist(id, title)
    }

    /** Places [occurrenceId] beside its neighbour in the playlist's complete custom order, as one committed write. */
    suspend fun movePlaylistItem(playlistId: String, occurrenceId: String, neighbourOccurrenceId: String, after: Boolean) {
        library.mutate(LibraryMutation.MoveInPlaylist(playlistId, occurrenceId, neighbourOccurrenceId, after))
    }

    /** Writes the auto-playlist order that every auto playlist shares. */
    suspend fun setAutoSongSort(sort: LibrarySortState<AutoPlaylistSongSortType>) {
        settings.edit { sort.writeTo(this, AutoPlaylistSongSortTypeKey, AutoPlaylistSongSortDescendingKey) }
    }

    /**
     * The saved songs that credit [artist], in [sort]'s order, emitted again with the library, listens and blocklist.
     * Only the credits the provider gave count — a name in the subtitle never stands in for an artist id, so a song
     * cannot land under an artist it does not credit. Null means [sort] needs data the store does not hold.
     */
    fun artistSongs(artist: ContentRef, sort: LibrarySortState<SongSortType>): Flow<List<ContentOccurrence>?> =
        library.observeLibrarySongs(sort, textOrder, blocked).map { page ->
            page?.items?.filter { row ->
                row.item.artists.any { credit -> credit.ref(row.item.ref.provider) == artist }
            }
        }

    /**
     * The saved-library albums that credit [artist], in [sort]'s order, as the artist page's Albums section lists
     * them. Null means [sort] needs data the store does not hold, as in [artistSongs].
     */
    fun artistAlbums(artist: ContentRef, sort: LibrarySortState<AlbumSortType>): Flow<List<LibraryAlbumRow>?> =
        library.observeLibraryAlbums(sort, textOrder, blocked).map { rows ->
            rows?.filter { row ->
                row.album.artists.any { credit -> credit.ref(row.album.ref.provider) == artist }
            }
        }

    /**
     * The songs of one My Top request, most played first and cut to its size. Null when the request names no My Top list
     * or the owner cannot answer, so playback refuses and the page shows a failure. The failure is logged by class name.
     */
    private suspend fun topList(request: ContentRequest): ContentPage? {
        val (period, size) = topParams(request) ?: return null
        return try {
            val now = nowMs()
            val window = period.window(now, offsetMsAt(now))
            val blockedNow = blocked.first()
            val songs = statistics.load(window).songs.filterNot { rank -> rank.item.hasBlockedArtist(blockedNow) }
            ContentPage(songs.take(size).map { rank -> keyed(rank.item) })
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            report("my top could not be read: ${failure::class.simpleName}")
            null
        }
    }

    /** The period and size a My Top request names, or null for any other request. */
    private fun topParams(request: ContentRequest): Pair<MyTopPeriod, Int>? {
        val section = request.section ?: return null
        if (request.surface != ContentSurface.STATISTICS || section.id != TOP_SECTION_ID) return null
        val parts = section.params?.split(':') ?: return null
        if (parts.size != 2) return null
        val period = MyTopPeriod.entries.firstOrNull { it.name == parts[0] } ?: return null
        val size = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return period to size
    }

    private fun chipList(filter: LibraryFilter, snapshot: PreferenceSnapshot): Pair<ContentRequest, Flow<ContentPage?>>? =
        when (filter) {
            LibraryFilter.SONGS -> songsChip(snapshot)
            LibraryFilter.ARTISTS -> local(ContentSurface.LIBRARY_ARTISTS) to artists(snapshot)
            LibraryFilter.ALBUMS -> local(ContentSurface.LIBRARY_ALBUMS) to albums(snapshot)
            LibraryFilter.PLAYLISTS -> local(ContentSurface.LIBRARY_PLAYLISTS) to playlists(snapshot)
            LibraryFilter.LIBRARY -> local(ContentSurface.LIBRARY_MIX) to mixed(snapshot)
        }

    private fun songsChip(snapshot: PreferenceSnapshot): Pair<ContentRequest, Flow<ContentPage?>> =
        when (snapshot[SongFilterKey].toEnum(LibraryDefaults.songFilter)) {
            SongFilter.LIBRARY -> local(ContentSurface.LIBRARY_SONGS) to librarySongs(snapshot)
            SongFilter.LIKED -> local(ContentSurface.LIBRARY_LIKED) to likedSongsWith(songSortAsAuto(snapshot))
            // Downloads keep their own surface, so playing this section plays its downloads, never the whole library.
            SongFilter.DOWNLOADED -> local(ContentSurface.DOWNLOADS) to downloadedSongs(snapshot)
        }

    private fun listFlow(request: ContentRequest, snapshot: PreferenceSnapshot): Flow<ContentPage?>? =
        when (request.surface) {
            ContentSurface.LIBRARY_SONGS -> librarySongs(snapshot)
            ContentSurface.LIBRARY_LIKED -> likedSongsWith(songSortAsAuto(snapshot))
            ContentSurface.LIBRARY_ARTISTS -> artists(snapshot)
            ContentSurface.LIBRARY_ALBUMS -> albums(snapshot)
            ContentSurface.LIBRARY_PLAYLISTS -> playlists(snapshot)
            ContentSurface.LIBRARY_PLAYLIST_ITEMS -> request.parent?.let { playlistItems(it.id, snapshot) }
            ContentSurface.DOWNLOADS -> downloadedSongs(snapshot)
            else -> null
        }

    private fun librarySongs(snapshot: PreferenceSnapshot): Flow<ContentPage?> = library.observeLibrarySongs(
        snapshot.librarySortState(SongSortTypeKey, SongSortDescendingKey, LibraryDefaults.songSort),
        textOrder,
        blocked,
    )

    private fun likedSongsWith(sort: LibrarySortState<AutoPlaylistSongSortType>): Flow<ContentPage?> =
        library.observeLikedSongs(sort, textOrder, blocked)

    /** The downloaded songs in the Songs sort. A missing download index is a null page, which the screen shows as failed. */
    private fun downloadedSongs(snapshot: PreferenceSnapshot): Flow<ContentPage?> = library.observeDownloadedSongs(
        snapshot.librarySortState(SongSortTypeKey, SongSortDescendingKey, LibraryDefaults.songSort),
        textOrder,
        blocked,
    )

    /** The Songs section's liked view reads the Songs sort, converted to the auto-playlist type with the same names. */
    private fun songSortAsAuto(snapshot: PreferenceSnapshot): LibrarySortState<AutoPlaylistSongSortType> {
        val song = snapshot.librarySortState(SongSortTypeKey, SongSortDescendingKey, LibraryDefaults.songSort)
        return LibrarySortState(AutoPlaylistSongSortType.valueOf(song.type.name), song.descending)
    }

    private fun autoSort(snapshot: PreferenceSnapshot): LibrarySortState<AutoPlaylistSongSortType> =
        snapshot.librarySortState(
            AutoPlaylistSongSortTypeKey,
            AutoPlaylistSongSortDescendingKey,
            LibraryDefaults.autoPlaylistSongSort,
        )

    private fun artists(snapshot: PreferenceSnapshot): Flow<ContentPage?> {
        val sort = snapshot.librarySortState(ArtistSortTypeKey, ArtistSortDescendingKey, LibraryDefaults.artistSort)
        // Liked artists come from the likes. The artists of saved songs would only be a guess at them.
        val rows = when (snapshot[ArtistFilterKey].toEnum(LibraryDefaults.artistFilter)) {
            ArtistFilter.LIBRARY -> library.observeLibraryArtists(sort, textOrder, blocked)
            ArtistFilter.LIKED -> library.observeLikedArtists(sort, textOrder, blocked)
        }
        return rows.map { list -> list?.let { ContentPage(it.map { row -> keyed(row.artist) }) } }
    }

    private fun albums(snapshot: PreferenceSnapshot): Flow<ContentPage?> {
        val sort = snapshot.librarySortState(AlbumSortTypeKey, AlbumSortDescendingKey, LibraryDefaults.albumSort)
        // Each filter reads its own owner list, so the page and playback of the same filter show the same albums.
        val rows = when (snapshot[AlbumFilterKey].toEnum(LibraryDefaults.albumFilter)) {
            AlbumFilter.LIBRARY -> library.observeLibraryAlbums(sort, textOrder, blocked)
            AlbumFilter.LIKED -> library.observeLikedAlbums(sort, textOrder, blocked)
            AlbumFilter.DOWNLOADED -> library.observeDownloadedAlbums(sort, textOrder, blocked)
            AlbumFilter.DOWNLOADED_FULL -> library.observeDownloadedFullAlbums(sort, textOrder, blocked)
        }
        return rows.map { list -> list?.let { ContentPage(it.map { row -> keyed(row.album) }) } }
    }

    /**
     * The mixed Library section as one flat list of the owner's own rows: local playlists, then saved artists, then
     * saved albums, each in its stored order. The baseline's shortcut cards, recent songs, top mixes and rails are not
     * drawn here. A section whose owner cannot answer is a failed page, never a partial list.
     */
    private fun mixed(snapshot: PreferenceSnapshot): Flow<ContentPage?> {
        val artistSort = snapshot.librarySortState(ArtistSortTypeKey, ArtistSortDescendingKey, LibraryDefaults.artistSort)
        val albumSort = snapshot.librarySortState(AlbumSortTypeKey, AlbumSortDescendingKey, LibraryDefaults.albumSort)
        return combine(
            playlists(snapshot),
            library.observeLibraryArtists(artistSort, textOrder, blocked),
            library.observeLibraryAlbums(albumSort, textOrder, blocked),
        ) { playlistPage, artistRows, albumRows ->
            if (playlistPage == null || artistRows == null || albumRows == null) {
                null
            } else {
                ContentPage(
                    playlistPage.items +
                        artistRows.map { row -> keyed(row.artist) } +
                        albumRows.map { row -> keyed(row.album) },
                )
            }
        }
    }

    /**
     * The Library's playlist section. Playlists the user hid stay stored and playable but are left out here, as
     * Android's Library leaves them out; the Playlists page, which owns the Show hidden control, still lists them.
     */
    private fun playlists(snapshot: PreferenceSnapshot): Flow<ContentPage?> {
        val sort = snapshot.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort)
        return library.observeLocalPlaylists().map { summaries ->
            ContentPage(orderedPlaylists(summaries.filterNot { it.hidden }, sort).map { summary -> summary.occurrence })
        }
    }

    /**
     * The custom order is the owner's own playlist order, which is by title, as its playlist list reads it. Every other
     * order is computed from the stored values, with the device collator for names, so the Library section and the
     * Playlists page order the same rows the same way.
     */
    private fun orderedPlaylists(
        summaries: List<LocalPlaylistSummary>,
        sort: LibrarySortState<PlaylistSortType>,
    ): List<LocalPlaylistSummary> {
        val ordered = when (sort.type) {
            PlaylistSortType.CUSTOM -> return summaries
            PlaylistSortType.NAME -> summaries.sortedWith(Comparator<LocalPlaylistSummary> { left, right ->
                textOrder.compare(left.occurrence.item.title, right.occurrence.item.title)
            })
            PlaylistSortType.CREATE_DATE -> summaries.sortedBy { summary -> summary.createdSeq }
            PlaylistSortType.SONG_COUNT -> summaries.sortedBy { summary -> summary.songCount }
            PlaylistSortType.LAST_UPDATED -> summaries.sortedBy { summary -> summary.newestAddedSeq }
        }
        return if (sort.descending) ordered.asReversed() else ordered
    }

    private fun playlistItems(playlistId: String, snapshot: PreferenceSnapshot): Flow<ContentPage?> =
        if (playlistId == LIKED_PLAYLIST_ID) likedSongsWith(autoSort(snapshot))
        else library.observeSortedPlaylist(playlistId, textOrder, blocked)
}

/** A local playlist as the screen needs it. [header] is null when the library holds no such playlist. */
data class LocalPlaylistView(
    val header: ContentItem?,
    val page: ContentPage?,
    val sort: LibrarySortState<PlaylistSongSortType>,
    val locked: Boolean,
)

/** The liked songs as the screen needs them, with the auto-playlist order that orders them. */
data class AutoPlaylistView(
    val page: ContentPage?,
    val sort: LibrarySortState<AutoPlaylistSongSortType>,
)

/**
 * The pseudo-playlist id of the liked songs in request and route identity. A slash keeps it apart from every stored
 * local playlist id, so a playlist can never be mistaken for the likes.
 */
const val LIKED_PLAYLIST_ID = "auto_playlist/liked"

/** The section id that names a My Top list inside its request. It is never a stored or shown identifier. */
private const val TOP_SECTION_ID = "my_top"

private fun local(surface: ContentSurface): ContentRequest = ContentRequest(ContentProvider.LOCAL, surface)

/** A stable key per owner item, so the same artist or album is never listed twice under one key. */
private fun keyed(item: ContentItem): ContentOccurrence =
    ContentOccurrence("${item.ref.provider}:${item.ref.kind.name}:${item.ref.id}", item)

/** An unavailable owner is a failed page. A complete one is initialized, with no continuation, as the owner returns it. */
fun ContentPage?.toPagedState(request: ContentRequest): PagedContentState =
    if (this == null) PagedContentState(request = request, failed = true)
    else PagedContentState(items = items, initialized = true, request = request)
