package moe.rukamori.archivetune.shared.domain.library

/**
 * The defaults a Library surface reads when its key was never written. Each value matches the default the Android
 * screen and view model use, so a fresh install shows the same Library.
 */
object LibraryDefaults {
    /** The Library reads YouTube Music until the user picks Spotify. */
    val source: LibrarySource = LibrarySource.YTM

    val songSort = LibrarySortState(SongSortType.CREATE_DATE, descending = true)
    val artistSort = LibrarySortState(ArtistSortType.CREATE_DATE, descending = true)
    val albumSort = LibrarySortState(AlbumSortType.CREATE_DATE, descending = true)
    val playlistSort = LibrarySortState(PlaylistSortType.CUSTOM, descending = true)
    val artistSongSort = LibrarySortState(ArtistSongSortType.CREATE_DATE, descending = true)
    val autoPlaylistSongSort = LibrarySortState(AutoPlaylistSongSortType.CREATE_DATE, descending = true)

    /** A playlist keeps its own order until the user sorts it, and the playlist stores that choice per playlist. */
    val playlistSongSort = LibrarySortState(PlaylistSongSortType.CUSTOM, descending = true)

    val songFilter: SongFilter = SongFilter.LIKED
    val artistFilter: ArtistFilter = ArtistFilter.LIKED
    val albumFilter: AlbumFilter = AlbumFilter.LIKED

    /** The chip a Library opens on. */
    val chipSortType: LibraryFilter = LibraryFilter.LIBRARY
}
