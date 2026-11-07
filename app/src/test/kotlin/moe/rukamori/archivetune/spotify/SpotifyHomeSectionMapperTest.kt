/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection
import moe.rukamori.archivetune.spotify.models.SpotifySimpleArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper used to keep only the kind a section held most of, so a shelf of six playlists and four
 * albums arrived at the screen as six playlists. These tests pin down that every item survives, in
 * Spotify's order, with the kind it draws as.
 */
class SpotifyHomeSectionMapperTest {
    private fun playlist(
        name: String,
        owner: String? = "Spotify",
        total: Int = 50,
    ) = SpotifyHomeFeedItem.Playlist(
        uri = "spotify:playlist:$name",
        id = name,
        name = "Playlist $name",
        description = "About $name",
        format = null,
        totalCount = total,
        imageUrl = "https://image/$name",
        extractedColorHex = null,
        ownerName = owner,
        madeForUsername = null,
    )

    private fun album(
        name: String,
        artist: String = "Artist $name",
    ) = SpotifyHomeFeedItem.Album(
        uri = "spotify:album:$name",
        id = name,
        name = "Album $name",
        albumType = "album",
        artists = listOf(SpotifySimpleArtist(id = "artist-$name", name = artist)),
        imageUrl = "https://image/$name",
    )

    private fun artist(name: String) = SpotifyHomeFeedItem.Artist(
        uri = "spotify:artist:$name",
        id = name,
        name = "Artist $name",
        imageUrl = "https://image/$name",
    )

    private fun section(
        title: String?,
        items: List<SpotifyHomeFeedItem>,
    ) = SpotifyHomeFeedSection(
        sectionUri = "spotify:section:test",
        title = title,
        typename = "HomeSection",
        totalCount = items.size,
        items = items,
    )

    @Test
    fun mixedShelfKeepsEveryItemInFeedOrder() {
        // Six playlists, four albums, in the order Spotify interleaves them.
        val feedItems =
            listOf(
                playlist("p1"),
                album("a1"),
                playlist("p2"),
                playlist("p3"),
                album("a2"),
                playlist("p4"),
                album("a3"),
                playlist("p5"),
                playlist("p6"),
                album("a4"),
            )

        val converted = convertHomeSection(section("Jump back in", feedItems))

        val expected =
            listOf(
                "playlist:p1",
                "album:a1",
                "playlist:p2",
                "playlist:p3",
                "album:a2",
                "playlist:p4",
                "album:a3",
                "playlist:p5",
                "playlist:p6",
                "album:a4",
            )
        assertEquals(expected, converted!!.items.map { it.kindAndId() })
        assertEquals(4, converted.items.count { it is SpotifyHomeSectionItem.Album })
        assertEquals(6, converted.items.count { it is SpotifyHomeSectionItem.Playlist })
    }

    @Test
    fun aShelfOfOneKindStillKeepsItsOrder() {
        val feedItems = listOf(artist("x"), artist("y"), artist("z"))

        val converted = convertHomeSection(section("spotify_top_artists", feedItems))

        assertEquals(
            listOf("artist:x", "artist:y", "artist:z"),
            converted!!.items.map { it.kindAndId() },
        )
    }

    @Test
    fun mappedPlaylistKeepsTheFieldsTheCardReads() {
        val feedItems = listOf(playlist("p1", owner = "Ruka", total = 12))

        val converted = convertHomeSection(section("spotify_your_playlists", feedItems))

        val playlist = (converted!!.items.single() as SpotifyHomeSectionItem.Playlist).playlist
        assertEquals("p1", playlist.id)
        assertEquals("Playlist p1", playlist.name)
        assertEquals("Ruka", playlist.owner?.displayName)
        assertEquals(12, playlist.tracks?.total)
        assertEquals(listOf("https://image/p1"), playlist.images.map { it.url })
        assertEquals("spotify:playlist:p1", playlist.uri)
    }

    @Test
    fun mappedAlbumAndArtistKeepTheirArtworkAndArtists() {
        val feedItems = listOf(album("a1", artist = "Ruka"), artist("z"))

        val converted = convertHomeSection(section("New releases", feedItems))

        val album = (converted!!.items[0] as SpotifyHomeSectionItem.Album).album
        assertEquals("a1", album.id)
        assertEquals(listOf("Ruka"), album.artists.map { it.name })
        assertEquals(listOf("https://image/a1"), album.images.map { it.url })

        val artist = (converted.items[1] as SpotifyHomeSectionItem.Artist).artist
        assertEquals("z", artist.id)
        assertEquals(listOf("https://image/z"), artist.images.map { it.url })
    }

    @Test
    fun aSectionWithoutATitleDrawsNothing() {
        assertNull(convertHomeSection(section(null, listOf(playlist("p1")))))
    }

    @Test
    fun aSectionWithoutItemsDrawsNothing() {
        assertNull(convertHomeSection(section("Empty", emptyList())))
    }

    @Test
    fun theFeedItemIsNotDroppedForBeingOutnumbered() {
        // One album in a shelf of eight playlists used to vanish; it is the case the old mapper lost.
        val feedItems = List(8) { playlist("p$it") } + album("a1")

        val converted = convertHomeSection(section("Made for you", feedItems))

        assertTrue(converted!!.items.last() is SpotifyHomeSectionItem.Album)
        assertEquals(9, converted.items.size)
    }

    private fun SpotifyHomeSectionItem.kindAndId(): String =
        when (this) {
            is SpotifyHomeSectionItem.Track -> "track:${track.id}"
            is SpotifyHomeSectionItem.Artist -> "artist:${artist.id}"
            is SpotifyHomeSectionItem.Album -> "album:${album.id}"
            is SpotifyHomeSectionItem.Playlist -> "playlist:${playlist.id}"
        }
}
