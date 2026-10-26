package moe.rukamori.archivetune.shared.domain.library

import moe.rukamori.archivetune.shared.domain.settings.AlbumFilterKey
import moe.rukamori.archivetune.shared.domain.settings.AlbumSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.AlbumSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.ChipSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SongFilterKey
import moe.rukamori.archivetune.shared.domain.settings.SongSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.SongSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes the Library's sort and filter state. Changing the sort type keeps the stored direction, custom orders
 * have no direction, and a stored value that no longer exists falls back to the default without rewriting the rest.
 */
class LibrarySortFilterTransitionsTest {
    @Test
    fun changingTheSortTypeKeepsTheStoredDirection() {
        val state = LibrarySortState(SongSortType.CREATE_DATE, descending = false)

        assertEquals(LibrarySortState(SongSortType.NAME, descending = false), state.selectType(SongSortType.NAME))
    }

    @Test
    fun togglingDirectionFlipsOnlyWhereADirectionIsOffered() {
        val byName = LibrarySortState(PlaylistSongSortType.NAME, descending = true)
        assertEquals(false, byName.toggleDescending().descending)

        val custom = LibrarySortState(PlaylistSongSortType.CUSTOM, descending = true)
        assertEquals(custom, custom.toggleDescending())
    }

    @Test
    fun customOrderIsNeverReversedWhateverTheStoredFlagSays() {
        assertFalse(LibrarySortState(PlaylistSongSortType.CUSTOM, descending = true).effectiveDescending)
        assertFalse(LibrarySortState(PlaylistSortType.CUSTOM, descending = true).effectiveDescending)
        assertTrue(LibrarySortState(PlaylistSortType.NAME, descending = true).effectiveDescending)
        assertFalse(LibrarySortState(PlaylistSortType.NAME, descending = false).effectiveDescending)
    }

    @Test
    fun theDirectionToggleIsShownOnlyForSortsThatHaveOne() {
        assertFalse(allowsSortDirection(PlaylistSongSortType.CUSTOM))
        assertFalse(allowsSortDirection(PlaylistSortType.CUSTOM))
        assertTrue(allowsSortDirection(PlaylistSongSortType.NAME))
        assertTrue(allowsSortDirection(PlaylistSortType.LAST_UPDATED))
        assertTrue(allowsSortDirection(SongSortType.CREATE_DATE))
        assertTrue(allowsSortDirection(AlbumSortType.LENGTH))
    }

    @Test
    fun aMissingSortReadsTheDefaults() {
        val state =
            PreferenceSnapshot.EMPTY.librarySortState(
                SongSortTypeKey,
                SongSortDescendingKey,
                LibraryDefaults.songSort,
            )

        assertEquals(LibraryDefaults.songSort, state)
        assertEquals(SongSortType.CREATE_DATE, state.type)
        assertTrue(state.descending)
    }

    @Test
    fun aStoredSortReadsItsTypeAndDirection() {
        val state =
            PreferenceSnapshot
                .of(ArtistSortTypeKey to "NAME", ArtistSortDescendingKey to false)
                .librarySortState(ArtistSortTypeKey, ArtistSortDescendingKey, LibraryDefaults.artistSort)

        assertEquals(LibrarySortState(ArtistSortType.NAME, descending = false), state)
    }

    @Test
    fun aRemovedSortTypeFallsBackButKeepsTheStoredDirection() {
        val state =
            PreferenceSnapshot
                .of(SongSortTypeKey to "REMOVED_SORT", SongSortDescendingKey to false)
                .librarySortState(SongSortTypeKey, SongSortDescendingKey, LibraryDefaults.songSort)

        assertEquals(LibrarySortState(SongSortType.CREATE_DATE, descending = false), state)
    }

    @Test
    fun choosingASortWritesTheTypeAndDirectionTogether() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()

        LibrarySortState(AlbumSortType.YEAR, descending = false).writeTo(editor, AlbumSortTypeKey, AlbumSortDescendingKey)

        assertEquals("YEAR", editor[AlbumSortTypeKey])
        assertEquals(false, editor[AlbumSortDescendingKey])
        assertEquals(
            LibrarySortState(AlbumSortType.YEAR, descending = false),
            editor.snapshot().librarySortState(AlbumSortTypeKey, AlbumSortDescendingKey, LibraryDefaults.albumSort),
        )
    }

    @Test
    fun playlistSongOrderDefaultsToCustomAndAscendingIsNeverApplied() {
        assertEquals(LibrarySortState(PlaylistSongSortType.CUSTOM, descending = true), LibraryDefaults.playlistSongSort)
        assertFalse(LibraryDefaults.playlistSongSort.effectiveDescending)
    }

    @Test
    fun filtersDecodeByNameAndFallBackToTheirDefault() {
        assertEquals(SongFilter.LIKED, PreferenceSnapshot.EMPTY[SongFilterKey].toEnum(LibraryDefaults.songFilter))
        assertEquals(
            SongFilter.DOWNLOADED,
            PreferenceSnapshot.of(SongFilterKey to "DOWNLOADED")[SongFilterKey].toEnum(LibraryDefaults.songFilter),
        )
        assertEquals(
            SongFilter.LIKED,
            PreferenceSnapshot.of(SongFilterKey to "gone")[SongFilterKey].toEnum(LibraryDefaults.songFilter),
        )
    }

    @Test
    fun theDefaultChipIsTheLibrarySection() {
        assertEquals(LibraryFilter.LIBRARY, LibraryDefaults.chipSortType)
        assertEquals(
            LibraryFilter.PLAYLISTS,
            PreferenceSnapshot.of(ChipSortTypeKey to "PLAYLISTS")[ChipSortTypeKey].toEnum(LibraryDefaults.chipSortType),
        )
        assertEquals(
            LibraryFilter.LIBRARY,
            PreferenceSnapshot.of(ChipSortTypeKey to "SPOTIFY")[ChipSortTypeKey].toEnum(LibraryDefaults.chipSortType),
        )
    }
}
