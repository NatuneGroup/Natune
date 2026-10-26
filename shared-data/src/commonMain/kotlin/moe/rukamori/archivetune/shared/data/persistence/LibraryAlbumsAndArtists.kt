package moe.rukamori.archivetune.shared.data.persistence

import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.ArtistSortType
import moe.rukamori.archivetune.shared.domain.library.LibraryAlbumRow
import moe.rukamori.archivetune.shared.domain.library.LibraryArtistRow
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistSongOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist

/** The id prefix of a YouTube channel artist, which the baseline's library artists always carry. */
private const val YOUTUBE_CHANNEL_PREFIX = "UC"

/** The id prefix of a privately owned YouTube library artist, which the baseline also lists. */
private const val PRIVATELY_OWNED_ARTIST_PREFIX = "FEmusic_library_privately_owned_artist"

/**
 * A saved song with the sequence the album and artist orders read, which is when the song entered the library. Albums
 * and artists are derived from these songs, so the library keeps no album or artist table of its own.
 */
internal class SavedSong(val item: ContentItem, val addedSeq: Long)

/**
 * The album rows of the saved library, derived from its songs. An album is the group of saved songs that share a
 * provider album id, so a song without one is in no album, as the baseline's album rows required an id. An album is
 * hidden when any of its saved songs credits a blocked artist. Null means [sort] needs data this store does not hold
 * (YEAR, or PLAY_TIME without listens), so the caller shows no list rather than another order.
 */
internal fun libraryAlbumRows(
    songs: List<SavedSong>,
    playTotals: Map<String, Long>?,
    blocked: Set<ContentRef>,
    sort: LibrarySortState<AlbumSortType>,
    collation: Comparator<String>,
): List<LibraryAlbumRow>? {
    val order = albumOrder(sort.type, collation) ?: return null
    if (sort.type == AlbumSortType.PLAY_TIME && playTotals == null) return null
    val albums = songs
        .mapNotNull { song -> song.item.albumRef()?.let { ref -> ref to song } }
        .groupBy({ it.first }, { it.second })
        .filterValues { members -> members.none { it.item.hasBlockedArtist(blocked) } }
        .map { (ref, members) -> albumEntry(ref, members.sortedBy { it.addedSeq }, playTotals) }
    return orderedRows(albums, order, sort.descending) { it.row }
}

/**
 * The artist rows of the saved library. Only the artists the baseline lists are shown: YouTube artists and local
 * artists. A blocked artist is hidden, but a saved song still counts for every other artist it credits, as the
 * baseline's song counts did. Null means PLAY_TIME without listens, so the caller shows no list.
 */
internal fun libraryArtistRows(
    songs: List<SavedSong>,
    playTotals: Map<String, Long>?,
    blocked: Set<ContentRef>,
    sort: LibrarySortState<ArtistSortType>,
    collation: Comparator<String>,
): List<LibraryArtistRow>? {
    if (sort.type == ArtistSortType.PLAY_TIME && playTotals == null) return null
    val artists = songs
        .flatMap { song ->
            song.item.artists
                .mapNotNull { credit -> credit.ref(song.item.ref.provider) }
                .distinct()
                .map { ref -> ref to song }
        }
        .groupBy({ it.first }, { it.second })
        .filterKeys { ref -> isListedArtist(ref) && ref !in blocked }
        .map { (ref, members) -> artistEntry(ref, members.sortedBy { it.addedSeq }, playTotals) }
    return orderedRows(artists, artistOrder(sort.type, collation), sort.descending) { it.row }
}

/**
 * The liked albums, each listed from the album its provider gave, whether or not any of its songs is saved. [bookmarks]
 * are the liked album rows, and their sequence is when each was liked. The songs and play totals come from the saved
 * library, so an album with no saved song counts zero songs and has no length. An album is hidden when the credits its
 * provider gave name a blocked artist, which is the baseline's rule for liked albums. Null means [sort] needs data this
 * store does not hold, as in [libraryAlbumRows].
 */
internal fun likedAlbumRows(
    bookmarks: List<SavedSong>,
    songs: List<SavedSong>,
    playTotals: Map<String, Long>?,
    blocked: Set<ContentRef>,
    sort: LibrarySortState<AlbumSortType>,
    collation: Comparator<String>,
): List<LibraryAlbumRow>? {
    val order = albumOrder(sort.type, collation) ?: return null
    if (sort.type == AlbumSortType.PLAY_TIME && playTotals == null) return null
    val members = songs
        .mapNotNull { song -> song.item.albumRef()?.let { ref -> ref to song } }
        .groupBy({ it.first }, { it.second })
    val albums = bookmarks
        .filterNot { it.item.hasBlockedArtist(blocked) }
        .map { bookmark -> likedAlbumEntry(bookmark, members[bookmark.item.ref].orEmpty(), playTotals) }
    return orderedRows(albums, order, sort.descending) { it.row }
}

/**
 * The liked artists, each listed from the artist its provider gave. As the baseline's liked artists were, only YouTube
 * and local artists are listed, and a blocked artist is hidden. [bookmarks] are the liked artist rows, and their
 * sequence is when each was liked. The song count and play time count the saved songs that credit the artist, so an
 * artist with no saved song counts zero. Null means PLAY_TIME without listens, as in [libraryArtistRows].
 */
internal fun likedArtistRows(
    bookmarks: List<SavedSong>,
    songs: List<SavedSong>,
    playTotals: Map<String, Long>?,
    blocked: Set<ContentRef>,
    sort: LibrarySortState<ArtistSortType>,
    collation: Comparator<String>,
): List<LibraryArtistRow>? {
    if (sort.type == ArtistSortType.PLAY_TIME && playTotals == null) return null
    val credited = songs
        .flatMap { song ->
            song.item.artists
                .mapNotNull { credit -> credit.ref(song.item.ref.provider) }
                .distinct()
                .map { ref -> ref to song }
        }
        .groupBy({ it.first }, { it.second })
    val artists = bookmarks
        .filter { isListedArtist(it.item.ref) && it.item.ref !in blocked }
        .map { bookmark -> likedArtistEntry(bookmark, credited[bookmark.item.ref].orEmpty(), playTotals) }
    return orderedRows(artists, artistOrder(sort.type, collation), sort.descending) { it.row }
}

/** Sorts ascending, then reverses the whole list for a descending order, as the baseline's library lists did. */
private fun <T, R> orderedRows(
    entries: List<T>,
    order: Comparator<T>,
    descending: Boolean,
    row: (T) -> R,
): List<R> {
    val ascending = entries.sortedWith(order)
    return (if (descending) ascending.reversed() else ascending).map(row)
}

/** One album before ordering, with the keys its sort reads. */
private class AlbumEntry(val row: LibraryAlbumRow, val createdSeq: Long, val artistText: String)

private fun albumEntry(ref: ContentRef, members: List<SavedSong>, playTotals: Map<String, Long>?): AlbumEntry {
    val title = members.firstNotNullOfOrNull { it.item.album?.takeIf(String::isNotBlank) }.orEmpty()
    val credits = members.flatMap { it.item.artists }.distinctBy { it.identityKey() }
    val album = ContentItem(
        ref = ref,
        title = title,
        subtitle = credits.joinToString(", ") { it.name },
        artworkUri = members.firstNotNullOfOrNull { it.item.artworkUri },
        explicit = members.any { it.item.explicit },
        album = title.takeIf(String::isNotBlank),
        artists = credits,
        albumId = ref.id,
    )
    val durations = members.mapNotNull { it.item.durationMs }
    return AlbumEntry(
        row = LibraryAlbumRow(
            album = album,
            songCount = members.size,
            durationMs = durations.takeIf { it.isNotEmpty() }?.sum(),
            playTimeMs = playTotals?.let { playTimeOf(members, it) },
        ),
        createdSeq = members.first().addedSeq,
        // The baseline sorted album artists with no separator between their names, so the order keeps that join.
        artistText = credits.joinToString("") { it.name },
    )
}

/**
 * One liked album before ordering. Its title, credits and artwork are the ones its provider gave, and the saved songs
 * fill in only what that record lacks, so an album with no provider credits still sorts by its songs' artists.
 */
private fun likedAlbumEntry(
    bookmark: SavedSong,
    members: List<SavedSong>,
    playTotals: Map<String, Long>?,
): AlbumEntry {
    val album = bookmark.item.let { item ->
        item.copy(
            artworkUri = item.artworkUri ?: members.firstNotNullOfOrNull { it.item.artworkUri },
            artists = item.artists.ifEmpty { members.flatMap { it.item.artists }.distinctBy { it.identityKey() } },
        )
    }
    val durations = members.mapNotNull { it.item.durationMs }
    return AlbumEntry(
        row = LibraryAlbumRow(
            album = album,
            songCount = members.size,
            durationMs = durations.takeIf { it.isNotEmpty() }?.sum(),
            playTimeMs = playTotals?.let { playTimeOf(members, it) },
        ),
        createdSeq = bookmark.addedSeq,
        // The baseline sorted album artists with no separator between their names, so the order keeps that join.
        artistText = album.artists.joinToString("") { it.name },
    )
}

/**
 * The album order. Ties fall back to CREATE_DATE and then to the album key, so equal rows keep a fixed order. YEAR has
 * no order because the portable library stores no release year, so it returns null.
 */
private fun albumOrder(type: AlbumSortType, collation: Comparator<String>): Comparator<AlbumEntry>? {
    val primary = when (type) {
        AlbumSortType.CREATE_DATE -> compareBy<AlbumEntry> { it.createdSeq }
        AlbumSortType.NAME -> Comparator<AlbumEntry> { left, right ->
            collation.compare(left.row.album.title, right.row.album.title)
        }
        AlbumSortType.ARTIST -> Comparator<AlbumEntry> { left, right ->
            collation.compare(left.artistText, right.artistText)
        }
        AlbumSortType.SONG_COUNT -> compareBy<AlbumEntry> { it.row.songCount }
        AlbumSortType.LENGTH -> compareBy<AlbumEntry> { it.row.durationMs ?: 0L }
        AlbumSortType.PLAY_TIME -> compareBy<AlbumEntry> { it.row.playTimeMs ?: 0L }
        AlbumSortType.YEAR -> return null
    }
    return primary.thenBy { it.createdSeq }.thenBy { PlaylistSongOrder.playKey(it.row.album.ref) }
}

/** One artist before ordering, with the sequence its CREATE_DATE order reads. */
private class ArtistEntry(val row: LibraryArtistRow, val createdSeq: Long)

private fun artistEntry(ref: ContentRef, members: List<SavedSong>, playTotals: Map<String, Long>?): ArtistEntry {
    val name = members.firstNotNullOf { song -> song.item.artists.firstOrNull { it.id == ref.id }?.name }
    return ArtistEntry(
        row = LibraryArtistRow(
            artist = ContentItem(
                ref = ref,
                title = name,
                artworkUri = members.firstNotNullOfOrNull { it.item.artworkUri },
            ),
            songCount = members.size,
            playTimeMs = playTotals?.let { playTimeOf(members, it) },
        ),
        createdSeq = members.first().addedSeq,
    )
}

/** One liked artist before ordering. Its name and artwork are the ones its provider gave. */
private fun likedArtistEntry(
    bookmark: SavedSong,
    members: List<SavedSong>,
    playTotals: Map<String, Long>?,
): ArtistEntry {
    val artist = bookmark.item.let { item ->
        item.copy(artworkUri = item.artworkUri ?: members.firstNotNullOfOrNull { it.item.artworkUri })
    }
    return ArtistEntry(
        row = LibraryArtistRow(
            artist = artist,
            songCount = members.size,
            playTimeMs = playTotals?.let { playTimeOf(members, it) },
        ),
        createdSeq = bookmark.addedSeq,
    )
}

private fun artistOrder(type: ArtistSortType, collation: Comparator<String>): Comparator<ArtistEntry> {
    val primary = when (type) {
        ArtistSortType.CREATE_DATE -> compareBy<ArtistEntry> { it.createdSeq }
        ArtistSortType.NAME -> Comparator<ArtistEntry> { left, right ->
            collation.compare(left.row.artist.title, right.row.artist.title)
        }
        ArtistSortType.SONG_COUNT -> compareBy<ArtistEntry> { it.row.songCount }
        ArtistSortType.PLAY_TIME -> compareBy<ArtistEntry> { it.row.playTimeMs ?: 0L }
    }
    return primary.thenBy { it.createdSeq }.thenBy { PlaylistSongOrder.playKey(it.row.artist.ref) }
}

/**
 * Whether the library lists the artist [ref]. Artists of other providers stay on their songs but are not listed,
 * because the baseline's library artists were only YouTube or local artists.
 */
private fun isListedArtist(ref: ContentRef): Boolean = when (ref.provider) {
    ContentProvider.LOCAL.id -> true
    ContentProvider.YOUTUBE.id -> ref.id.startsWith(YOUTUBE_CHANNEL_PREFIX) ||
        ref.id.startsWith(PRIVATELY_OWNED_ARTIST_PREFIX)
    else -> false
}

/** The all-time play time of [members], each song counted once. */
private fun playTimeOf(members: List<SavedSong>, totals: Map<String, Long>): Long =
    members.sumOf { totals[PlaylistSongOrder.playKey(it.item.ref)] ?: 0L }

/** The same provider id is the same credit; an unidentified credit is told apart by its name. */
private fun ContentCredit.identityKey(): String = id?.let { "id:$it" } ?: "name:$name"
