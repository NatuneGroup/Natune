package moe.rukamori.archivetune.shared.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterizes the Library chip order. The stored order keeps the sections the user moved, drops unknown or repeated
 * names, and appends any section that is missing in the default order, so an upgrade never hides a section.
 */
class LibraryFilterOrderTest {
    @Test
    fun theDefaultOrderIsLibraryPlaylistsSongsArtistsAlbums() {
        assertEquals(
            listOf(
                LibraryFilter.LIBRARY,
                LibraryFilter.PLAYLISTS,
                LibraryFilter.SONGS,
                LibraryFilter.ARTISTS,
                LibraryFilter.ALBUMS,
            ),
            DefaultLibraryFilterOrder,
        )
        assertEquals("LIBRARY,PLAYLISTS,SONGS,ARTISTS,ALBUMS", DefaultLibraryFilterOrderPreference)
    }

    @Test
    fun aSavedOrderIsKeptAndMissingSectionsAreAppendedInDefaultOrder() {
        assertEquals(
            listOf(
                LibraryFilter.SONGS,
                LibraryFilter.ALBUMS,
                LibraryFilter.LIBRARY,
                LibraryFilter.PLAYLISTS,
                LibraryFilter.ARTISTS,
            ),
            "SONGS,ALBUMS".toLibraryFilterOrder(),
        )
    }

    @Test
    fun unknownAndRepeatedNamesAreDroppedWhileSurroundingSpacesAreIgnored() {
        assertEquals(
            listOf(
                LibraryFilter.ARTISTS,
                LibraryFilter.PLAYLISTS,
                LibraryFilter.LIBRARY,
                LibraryFilter.SONGS,
                LibraryFilter.ALBUMS,
            ),
            "ARTISTS,bogus,ARTISTS, PLAYLISTS ".toLibraryFilterOrder(),
        )
    }

    @Test
    fun blankOrRemovedNamesFallBackToTheDefaultOrder() {
        assertEquals(DefaultLibraryFilterOrder, "".toLibraryFilterOrder())
        assertEquals(DefaultLibraryFilterOrder, "SPOTIFY".toLibraryFilterOrder())
    }

    @Test
    fun aStoredPreferenceIsDistinctAndCompletedWithTheDefaults() {
        assertEquals(
            "ALBUMS,SONGS,LIBRARY,PLAYLISTS,ARTISTS",
            listOf(LibraryFilter.ALBUMS, LibraryFilter.ALBUMS, LibraryFilter.SONGS).toLibraryFilterPreference(),
        )
    }

    @Test
    fun aWrittenOrderReadsBackUnchanged() {
        val order = listOf(LibraryFilter.PLAYLISTS, LibraryFilter.ARTISTS, LibraryFilter.LIBRARY, LibraryFilter.ALBUMS, LibraryFilter.SONGS)

        assertEquals(order, order.toLibraryFilterPreference().toLibraryFilterOrder())
    }
}
