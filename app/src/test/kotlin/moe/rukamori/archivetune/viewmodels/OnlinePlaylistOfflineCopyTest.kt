package moe.rukamori.archivetune.viewmodels

import moe.rukamori.archivetune.db.fullestPlaylistCopy
import moe.rukamori.archivetune.db.playlistBrowseIdForms
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePlaylistOfflineCopyTest {
    @Test
    fun browsePrefixedAndBareIdsLookUpTheSameStoredForms() {
        assertEquals(listOf("VLPL123", "PL123"), playlistBrowseIdForms("VLPL123"))
        assertEquals(listOf("PL123", "VLPL123"), playlistBrowseIdForms("PL123"))
        assertEquals(listOf("VLVLPL123", "PL123", "VLPL123"), playlistBrowseIdForms("VLVLPL123"))
        assertEquals(playlistBrowseIdForms("VLPL123").toSet(), playlistBrowseIdForms("PL123").toSet())
    }

    @Test
    fun blankOrPrefixOnlyIdsHaveNoLookupForm() {
        assertEquals(emptyList<String>(), playlistBrowseIdForms(""))
        assertEquals(emptyList<String>(), playlistBrowseIdForms("VL"))
    }

    @Test
    fun theFullestStoredCopyWinsAndATieKeepsTheRequestedForm() {
        val requested = "requested" to 3
        val other = "other" to 40
        assertEquals(other, fullestPlaylistCopy(listOf(requested, null, other)) { it.second })
        assertEquals(requested, fullestPlaylistCopy(listOf(requested, "tie" to 3)) { it.second })
        assertNull(fullestPlaylistCopy(listOf<Pair<String, Int>?>(null, null)) { it.second })
    }

    @Test
    fun aFailedFetchKeepsTheSavedMembershipAndOnlyErrorsWithoutOne() {
        assertEquals(
            OnlinePlaylistSongsSource.OFFLINE_COPY,
            onlinePlaylistSongsSource(fetchSucceeded = false, fetchedSongCount = 0, fetchConfirmedEmpty = false, savedMembershipSize = 12),
        )
        assertEquals(
            OnlinePlaylistSongsSource.NONE,
            onlinePlaylistSongsSource(fetchSucceeded = false, fetchedSongCount = 0, fetchConfirmedEmpty = false, savedMembershipSize = 0),
        )
    }

    @Test
    fun aSuccessfulFetchWithSongsBehavesAsBefore() {
        assertEquals(
            OnlinePlaylistSongsSource.REMOTE,
            onlinePlaylistSongsSource(fetchSucceeded = true, fetchedSongCount = 3, fetchConfirmedEmpty = false, savedMembershipSize = 120),
        )
        assertEquals(
            OnlinePlaylistSongsSource.REMOTE,
            onlinePlaylistSongsSource(fetchSucceeded = true, fetchedSongCount = 0, fetchConfirmedEmpty = false, savedMembershipSize = 0),
        )
    }

    @Test
    fun anUnconfirmedEmptyPageNeverHidesACompleteSavedMembership() {
        assertEquals(
            OnlinePlaylistSongsSource.OFFLINE_COPY,
            onlinePlaylistSongsSource(fetchSucceeded = true, fetchedSongCount = 0, fetchConfirmedEmpty = false, savedMembershipSize = 12),
        )
        assertEquals(
            OnlinePlaylistSongsSource.REMOTE,
            onlinePlaylistSongsSource(fetchSucceeded = true, fetchedSongCount = 0, fetchConfirmedEmpty = true, savedMembershipSize = 12),
        )
    }

    @Test
    fun onlyAnExhaustedPageWithAnExplicitZeroCountIsConfirmedEmpty() {
        assertTrue(page(songs = emptyList(), count = "0 songs").isConfirmedEmpty())
        assertFalse(page(songs = emptyList(), count = null).isConfirmedEmpty())
        assertFalse(page(songs = emptyList(), count = "0 songs", continuation = "next").isConfirmedEmpty())
        assertFalse(page(songs = listOf(song()), count = "0 songs").isConfirmedEmpty())
    }

    private fun song() = SongItem(id = "v1", title = "Track", artists = emptyList(), thumbnail = "")

    private fun page(
        songs: List<SongItem>,
        count: String?,
        continuation: String? = null,
    ) = PlaylistPage(
        playlist =
            PlaylistItem(
                id = "PL123",
                title = "Saved",
                author = null,
                songCountText = count,
                thumbnail = null,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            ),
        songs = songs,
        songsContinuation = continuation,
        continuation = null,
    )
}
