package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMetadataUpdatePolicyTest {
    private val presentation = QueueMetadataPresentation("Song", "Artist", "Artist", "Album", "artwork")

    @Test
    fun repeatedCatalogResultsDoNotRepublishAnyOccurrence() {
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(), false))
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(), true))
    }

    @Test
    fun artworkOnlyUpdatesWaitUntilTheOccurrenceIsCurrent() {
        val resolvedArtwork = presentation.copy(artworkUri = "resolved-artwork")
        assertFalse(queueMetadataNeedsReplacement(presentation, resolvedArtwork, false))
        assertTrue(queueMetadataNeedsReplacement(presentation, resolvedArtwork, true))
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(artworkUri = null), false))
    }

    @Test
    fun realNameAndAlbumEditsReachUpcomingOccurrences() {
        for (updated in listOf(
            presentation.copy(title = "Edited"),
            presentation.copy(subtitle = "Edited"),
            presentation.copy(artist = "Edited", artworkUri = "resolved-artwork"),
            presentation.copy(albumTitle = "Edited"),
        )) {
            assertTrue(queueMetadataNeedsReplacement(presentation, updated, false))
            assertTrue(queueMetadataNeedsReplacement(presentation, updated, true))
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
        assertFalse(queueMetadataNeedsReplacement(presentation, presentation.copy(artworkUri = effective.thumbnailUrl), false))
    }

    @Test
    fun duplicateOccurrencesAreBatchedWithInterveningRowsIntact() {
        data class Row(val id: String, val title: String)
        val original = listOf(
            Row("before", "Before"),
            Row("song", "Old"),
            Row("between", "Between"),
            Row("song", "Old"),
            Row("after", "After"),
        )
        val batch = queueMetadataReplacementBatch(
            original,
            mapOf(1 to original[1].copy(title = "Edited"), 3 to original[3].copy(title = "Edited")),
        )!!

        assertEquals(1, batch.fromIndex)
        assertEquals(4, batch.toIndex)
        assertEquals(listOf("song", "between", "song"), batch.items.map { it.id })
        assertEquals(listOf("Edited", "Between", "Edited"), batch.items.map { it.title })
        assertSame(original[2], batch.items[1])
        assertNull(queueMetadataReplacementBatch(original, emptyMap()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBatchCannotReplaceAnOccurrenceOutsideTheQueue() {
        queueMetadataReplacementBatch(listOf("song"), mapOf(Int.MAX_VALUE to "edited"))
    }

    private fun metadata() = MediaMetadata("song-id", "Song", listOf(MediaMetadata.Artist("artist-id", "Artist")), 180)
}
