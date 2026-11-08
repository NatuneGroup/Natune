package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeArtistCreditsTest {
    private data class Run(val text: String, val id: String? = null)

    @Test fun skipsTypeAlbumDurationAndSeparatorsButKeepsAllLinkedArtistsInOrder() {
        val groups = listOf(listOf(Run("Song")), listOf(Run("First", "UC-first"), Run(" & "),
            Run("Second", "FEMC-second")), listOf(Run("Album", "MPRE-album")),
            listOf(Run("First", "UC-first")), listOf(Run("3:23")))
        val artists = youTubeArtistCredits(groups, { it.text }, { it.id })
        assertEquals(listOf("First", "Second"), artists.map { it.name })
        assertEquals("First, Second", youTubeArtistSubtitle(artists, "Song"))
    }

    @Test fun doesNotInventAnArtistFromATypeLabelOrAlbum() {
        assertEquals("", youTubeArtistSubtitle(emptyList(), "Song"))
        assertEquals("Unlinked artist", youTubeArtistSubtitle(emptyList(), "Unlinked artist"))
        assertEquals(emptyList(), youTubeArtistCredits(listOf(listOf(Run("Album", "MPRE-album"))), { it.text }, { it.id }))
    }
}
