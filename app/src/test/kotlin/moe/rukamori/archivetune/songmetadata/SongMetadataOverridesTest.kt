package moe.rukamori.archivetune.songmetadata

import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.*
import org.junit.Test

class SongMetadataOverridesTest {
    private val provider = MediaMetadata(
        id = "song", title = "Provider title",
        artists = listOf(MediaMetadata.Artist("artist", "Provider artist"), MediaMetadata.Artist("guest", "Guest")),
        duration = 180, album = MediaMetadata.Album("album", "Album"),
        thumbnailUrl = "https://example.test/cover",
    )

    @Test fun savesOnlyDifferingFieldsAndPreservesNamesContainingCommas() {
        assertNull(buildSongMetadataOverride("song", provider.title, listOf("Provider artist", "Guest"), provider.title, listOf("Provider artist", "Guest"), 1))
        val titleOnly = buildSongMetadataOverride("song", provider.title, listOf("Provider artist", "Guest"), " Edited ", listOf("Provider artist", "Guest"), 2)!!
        assertEquals("Edited", titleOnly.title)
        assertNull(titleOnly.artistNames)
        val artistsOnly = buildSongMetadataOverride("song", provider.title, listOf("Provider artist", "Guest"), provider.title, listOf(" A, B ", "", "Guest", "Guest"), 3)!!
        assertNull(artistsOnly.title)
        assertEquals(listOf("A, B", "Guest"), artistsOnly.artistNameList())
    }

    @Test fun aTitleEditFollowsRefreshedArtistsAndAnArtistEditFollowsRefreshedTitle() {
        val refreshed = provider.copy(title = "Refreshed", artists = listOf(MediaMetadata.Artist("new", "New artist")))
        val titleOnly = refreshed.withSongMetadataOverride(SongMetadataOverrideEntity("song", title = "Edited"))
        assertEquals("Edited", titleOnly.title)
        assertEquals(refreshed.artists, titleOnly.artists)
        val artistsOnly = refreshed.withSongMetadataOverride(SongMetadataOverrideEntity("song", artistNames = "Edited artist"))
        assertEquals("Refreshed", artistsOnly.title)
        assertEquals(listOf("Edited artist"), artistsOnly.artists.map { it.name })
        assertEquals(refreshed, artistsOnly.canonicalMetadata())
    }

    @Test fun canonicalWritesAndResetNeverUsePreviousDisplayNames() {
        val edited = provider.withSongMetadataOverride(SongMetadataOverrideEntity("song", "Edited", "Renamed\nGuest"))
        assertEquals("Provider title", edited.toSongEntity().title)
        assertNull(edited.artists.first().id)
        assertEquals("guest", edited.artists.last().id)
        assertEquals(provider, edited.withSongMetadataOverride(null))
        assertEquals(provider.artists, edited.withSongMetadataOverride(SongMetadataOverrideEntity("song", title = "Another edit")).artists)
    }

    @Test fun updatingMediaItemTagsKeepsEachOccurrencesPlaybackFields() {
        val edit = provider.withSongMetadataOverride(SongMetadataOverrideEntity("song", "Edited", "Artist"))
        val occurrences = listOf(
            provider.copy(setVideoId = "set-a", suggestedBy = "Alice", spotifyTrackId = "catalog-a", isMusicVideo = true),
            provider.copy(setVideoId = "set-b", suggestedBy = "Bob", spotifyTrackId = "catalog-b", isPodcast = true),
        )
        val updated = occurrences.map { it.withEffectiveSongNames(edit) }
        assertEquals(listOf("Edited", "Edited"), updated.map { it.title })
        assertEquals(listOf("set-a", "set-b"), updated.map { it.setVideoId })
        assertEquals(listOf("Alice", "Bob"), updated.map { it.suggestedBy })
        assertEquals(listOf("catalog-a", "catalog-b"), updated.map { it.spotifyTrackId })
        assertTrue(updated.first().isMusicVideo)
        assertTrue(updated.last().isPodcast)
        assertEquals(occurrences, updated.map { it.withEffectiveSongNames(provider) })
    }
}
