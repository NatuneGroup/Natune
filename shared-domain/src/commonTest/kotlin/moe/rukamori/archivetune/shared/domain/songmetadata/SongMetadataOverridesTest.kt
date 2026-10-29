package moe.rukamori.archivetune.shared.domain.songmetadata

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*

class SongMetadataOverridesTest {
    private val source = ContentItem(
        ContentRef("youtube", ContentKind.SONG, "song"),
        "Provider title", "Provider artist, Guest",
        artworkUri = "https://example.test/cover", durationMs = 180_000, explicit = true,
        album = "Album", albumId = "album",
        artists = listOf(ContentCredit("Provider artist", "artist"), ContentCredit("Guest", "guest")),
    )

    @Test fun onlyDifferingFieldsAreStoredAndNamesAreNotCommaSplit() {
        assertNull(buildSongMetadataOverride(source, source.title, source.songArtistNames(), 1))
        val titleOnly = assertNotNull(buildSongMetadataOverride(source, "  Edited  ", source.songArtistNames(), 2))
        assertEquals("Edited", titleOnly.title)
        assertNull(titleOnly.artistNames)
        val artistsOnly = assertNotNull(buildSongMetadataOverride(source, source.title, listOf(" A, B ", "", "Guest", "Guest"), 3))
        assertNull(artistsOnly.title)
        assertEquals(listOf("A, B", "Guest"), artistsOnly.artistNames)
        assertEquals(3, artistsOnly.updatedAtMs)
    }

    @Test fun untouchedFieldsFollowProviderRefresh() {
        val refreshed = source.copy(title = "New provider title", subtitle = "New artist", artists = listOf(ContentCredit("New artist", "new")))
        val titleOnly = refreshed.withSongMetadataOverride(SongMetadataOverride(title = "Edited title"))
        assertEquals("Edited title", titleOnly.title)
        assertEquals(refreshed.artists, titleOnly.artists)
        val artistsOnly = refreshed.withSongMetadataOverride(SongMetadataOverride(artistNames = listOf("Edited artist")))
        assertEquals(refreshed.title, artistsOnly.title)
        assertEquals("Edited artist", artistsOnly.subtitle)
        assertEquals(refreshed, artistsOnly.canonicalSong())
    }

    @Test fun resetRestoresCanonicalNamesWithoutLosingIdentityOrOtherFields() {
        val edited = source.withSongMetadataOverride(SongMetadataOverride("Edited", listOf("Renamed", "Guest")))
        assertNull(edited.artists.first().id)
        assertEquals("guest", edited.artists.last().id)
        assertEquals(source.ref, edited.ref)
        assertEquals(source.artworkUri, edited.artworkUri)
        assertEquals(source.durationMs, edited.durationMs)
        assertEquals(source.albumId, edited.albumId)
        assertTrue(edited.explicit)
        assertEquals(source, edited.withSongMetadataOverride(null))
        // Applying another edit must start from the provider, not the previous display copy.
        assertEquals(source.artists, edited.withSongMetadataOverride(SongMetadataOverride(title = "Second edit")).artists)
    }

    @Test fun providersAndNonSongKindsCannotBorrowAnotherSongsEdit() {
        val edits = mapOf(source.ref to SongMetadataOverride("Edited"))
        val local = source.copy(ref = ContentRef("local", ContentKind.SONG, source.ref.id))
        assertEquals(source.title, local.withSongMetadataOverrides(edits).title)
        val album = source.copy(ref = ContentRef("youtube", ContentKind.ALBUM, source.ref.id))
        assertEquals(album, album.withSongMetadataOverride(SongMetadataOverride("Not an album edit")))
    }

    @Test fun duplicateOccurrencesKeepTheirOwnIdentifiersAndContinuation() {
        val page = ContentPage(listOf(ContentOccurrence("a", source, "set-a"), ContentOccurrence("b", source, "set-b")), "next")
        val projected = page.withSongMetadataOverrides(mapOf(source.ref to SongMetadataOverride("Edited", listOf("Artist"))))
        assertEquals(listOf("Edited", "Edited"), projected.items.map { it.item.title })
        assertEquals(listOf("a", "b"), projected.items.map { it.occurrenceId })
        assertEquals(listOf("set-a", "set-b"), projected.items.map { it.setVideoId })
        assertEquals("next", projected.continuation)
        assertEquals(page, projected.withSongMetadataOverrides(emptyMap()))
    }

    @Test fun editedSearchMatchesEffectiveNamesAndResetMatchesProviderAgain() {
        val edited = source.withSongMetadataOverride(SongMetadataOverride("My title", listOf("My artist")))
        assertTrue(edited.matchesSongSearch("MY TITLE"))
        assertTrue(edited.matchesSongSearch("my artist"))
        assertFalse(edited.matchesSongSearch("Provider"))
        assertTrue(edited.withSongMetadataOverride(null).matchesSongSearch("Provider"))
    }

    @Test fun renamingCreditsDoesNotBypassArtistBlocking() {
        val edited = source.withSongMetadataOverride(SongMetadataOverride(artistNames = listOf("Renamed")))
        assertTrue(edited.hasBlockedArtist(setOf(ContentRef("youtube", ContentKind.ARTIST, "artist"))))
        assertFalse(edited.hasBlockedArtist(setOf(ContentRef("local", ContentKind.ARTIST, "artist"))))
    }
}
