package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMetadataUpdatePolicyTest {
    private val presentation = QueueMetadataPresentation("Song", "Artist", "Artist", "Album", "artwork")

    @Test
    fun repeatedCatalogResultsDoNotRepublishAnyOccurrence() {
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy()))
    }

    @Test
    fun artworkOnlyUpdatesNeverReplacePlaylistItems() {
        val resolvedArtwork = presentation.copy(artworkUri = "resolved-artwork")
        assertFalse(queueMetadataNeedsReplacement(presentation, resolvedArtwork))
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(artworkUri = null)))
    }

    @Test
    fun realNameAndAlbumEditsReachUpcomingOccurrences() {
        for (updated in listOf(
            presentation.copy(title = "Edited"),
            presentation.copy(subtitle = "Edited"),
            presentation.copy(artist = "Edited", artworkUri = "resolved-artwork"),
            presentation.copy(albumTitle = "Edited"),
        )) {
            assertTrue(queueMetadataNeedsReplacement(presentation, updated))
        }
    }

    @Test
    fun aNamePublicationDoesNotLoseDeferredArtworkForTheNextTrack() {
        val upcoming = queueMetadataArtworkUri("old-artwork", "resolved-artwork", false, true)
        assertEquals("old-artwork", upcoming)
        assertEquals("resolved-artwork", queueMetadataArtworkUri(upcoming, "resolved-artwork", true, true))
        assertEquals("notification-sized-artwork", queueMetadataArtworkUri("notification-sized-artwork", "original-artwork", true, false))
        assertEquals("old-artwork", queueMetadataArtworkUri("old-artwork", null, true, true))
    }

    @Test
    fun invisibleCatalogFieldsAreRetainedWithoutChangingQueueIdentity() {
        val occurrence = metadata().copy(setVideoId = "playlist-occurrence", suggestedBy = "Listener")
        val resolved = metadata().copy(
            id = "catalog-id",
            duration = 240,
            spotifyTrackId = "spotify-track",
            thumbnailUrl = "new-artwork",
            setVideoId = "other-occurrence",
            suggestedBy = "Other listener",
        )
        val effective = resolvedQueueMetadataForOccurrence(occurrence, resolved)

        assertEquals("song-id", effective.id)
        assertEquals("playlist-occurrence", effective.setVideoId)
        assertEquals("Listener", effective.suggestedBy)
        assertEquals(240, effective.duration)
        assertEquals("spotify-track", effective.spotifyTrackId)
        assertEquals("new-artwork", effective.thumbnailUrl)
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(artworkUri = effective.thumbnailUrl)))
    }

    @Test
    fun duplicateOccurrencesOnlyReplaceContiguousChangedRows() {
        val original = listOf("Before", "A", "Playing B", "A", "A", "After")
        val batches = queueMetadataReplacementBatches(original, mapOf(1 to "Edited A", 3 to "Edited A", 4 to "Edited A"))

        assertEquals(listOf(1, 3), batches.map { it.fromIndex })
        assertEquals(listOf(2, 5), batches.map { it.toIndex })
        assertEquals(listOf(listOf("Edited A"), listOf("Edited A", "Edited A")), batches.map { it.items })
        assertTrue(batches.none { 2 in it.fromIndex until it.toIndex })
        assertTrue(queueMetadataReplacementBatches(original, emptyMap()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBatchCannotReplaceAnOccurrenceOutsideTheQueue() {
        queueMetadataReplacementBatches(listOf("song"), mapOf(Int.MAX_VALUE to "edited"))
    }

    private fun metadata() = MediaMetadata("song-id", "Song", listOf(MediaMetadata.Artist("artist-id", "Artist")), 180)
}
