package moe.rukamori.archivetune.shared.data.playback

import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowPlayingProjectionTest {
    private val snapshot = PlaybackSnapshot(
        occurrenceId = "track@1",
        mediaId = "track",
        playbackGeneration = 1,
        discontinuityGeneration = 0,
        positionMs = 1_000,
        sampleMonotonicNanos = 0,
        durationMs = 180_000,
        isSeekable = true,
        playbackSpeed = 1f,
        playWhenReady = true,
        isAudioAdvancing = true,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
    )
    private val item = ContentItem(
        ref = ContentRef("youtube", ContentKind.SONG, "track"),
        title = "Title",
        subtitle = "Artist",
        artworkUri = "https://images.example.test/cover.jpg",
        album = "Album",
    )

    @Test
    fun firstTrackPublishesMetadataTransportAndSeekability() {
        assertEquals(NowPlayingChanges(true, true, true), nowPlayingProjection(snapshot, item).changesFrom(null))
    }

    @Test
    fun ordinaryPlayingAndIdlePollsDoNotRepublish() {
        for (playing in listOf(true, false)) {
            val first = snapshot.copy(isAudioAdvancing = playing, playWhenReady = playing)
            val next = first.copy(positionMs = 1_250, sampleMonotonicNanos = 250_000_000, bufferedPositionMs = 5_000)
            val changes = nowPlayingProjection(next, item).changesFrom(nowPlayingProjection(first, item))
            assertEquals(NowPlayingChanges(false, false, false), changes)
            assertFalse(changes.publishInfo)
        }
    }

    @Test
    fun seekReanchorsTransportWithoutRebuildingMetadata() {
        val seek = snapshot.copy(positionMs = 80_000, discontinuityGeneration = 1)
        assertEquals(NowPlayingChanges(false, true, false), changes(seek))
    }

    @Test
    fun pauseResumeAndBufferingReanchorTheEffectiveRate() {
        val paused = snapshot.copy(playWhenReady = false, isAudioAdvancing = false)
        val buffering = snapshot.copy(isBuffering = true)
        for (state in listOf(paused, buffering)) {
            assertEquals(NowPlayingChanges(false, true, false), changes(state))
            assertEquals(0.0, nowPlayingProjection(state, item).transport.rate)
            assertEquals(
                NowPlayingChanges(false, true, false),
                nowPlayingProjection(snapshot, item).changesFrom(nowPlayingProjection(state, item)),
            )
        }
    }

    @Test
    fun intentChangesWhileBufferingAreNotLost() {
        val buffering = snapshot.copy(isBuffering = true)
        val cancelled = buffering.copy(playWhenReady = false)
        assertTrue(nowPlayingProjection(cancelled, item).changesFrom(nowPlayingProjection(buffering, item)).transport)
    }

    @Test
    fun playbackSpeedChangesEvenWhenTheOutputIsPaused() {
        val paused = snapshot.copy(isAudioAdvancing = false, playWhenReady = false)
        val faster = paused.copy(playbackSpeed = 1.5f)
        val value = nowPlayingProjection(faster, item)
        assertEquals(0.0, value.transport.rate)
        assertEquals(1.5, value.transport.defaultRate)
        assertEquals(NowPlayingChanges(false, true, false), value.changesFrom(nowPlayingProjection(paused, item)))
    }

    @Test
    fun seekabilityOnlyChangesTheRemoteCommand() {
        val changes = changes(snapshot.copy(isSeekable = false))
        assertEquals(NowPlayingChanges(false, false, true), changes)
        assertFalse(changes.publishInfo)
    }

    @Test
    fun outputLatencyChangesDoNotRewriteSystemMetadataOrTransport() {
        assertEquals(NowPlayingChanges(false, false, false), changes(snapshot.copy(outputLatencyMs = 2_000L)))
    }

    @Test
    fun metadataCorrectionsAndDiscoveredDurationPublishWithoutATransportChange() {
        val original = nowPlayingProjection(snapshot, item)
        for (replacement in listOf(item.copy(title = "Corrected"), item.copy(subtitle = "Credit"), item.copy(album = null))) {
            assertEquals(
                NowPlayingChanges(true, false, false),
                nowPlayingProjection(snapshot, replacement).changesFrom(original),
            )
        }
        assertEquals(NowPlayingChanges(true, false, false), changes(snapshot.copy(durationMs = 181_000)))
    }

    @Test
    fun newOccurrenceAndReloadRebuildMetadataEvenForTheSameMedia() {
        assertTrue(changes(snapshot.copy(occurrenceId = "track@2")).metadata)
        assertEquals(
            NowPlayingChanges(true, true, false),
            changes(snapshot.copy(playbackGeneration = 2)),
        )
    }

    @Test
    fun missingMetadataDoesNotRetainThePreviousTracksLabels() {
        val value = nowPlayingProjection(snapshot, null)
        assertNull(value.metadata.title)
        assertNull(value.metadata.artist)
        assertNull(value.metadata.album)
        assertNull(value.metadata.artworkKey)
        assertEquals(NowPlayingChanges(true, false, false), value.changesFrom(nowPlayingProjection(snapshot, item)))
    }

    @Test
    fun artworkKeyIsStableAcrossPollsSeeksAndRepeatedOccurrences() {
        val key = nowPlayingProjection(snapshot, item).metadata.artworkKey
        val repeated = snapshot.copy(occurrenceId = "track@2", playbackGeneration = 2, discontinuityGeneration = 4)
        assertEquals(key, nowPlayingProjection(repeated, item).metadata.artworkKey)
        assertEquals(key, NowPlayingArtworkKey.from("track", " ${item.artworkUri} "))
    }

    @Test
    fun artworkSourceAndMediaIdentityBothInvalidateTheCacheKey() {
        val key = NowPlayingArtworkKey.from("track", item.artworkUri)
        assertNotEquals(key, NowPlayingArtworkKey.from("other", item.artworkUri))
        assertNotEquals(key, NowPlayingArtworkKey.from("track", "${item.artworkUri}?version=2"))
    }

    @Test
    fun absentSourcesNeverBecomeAnArtworkRequest() {
        assertNull(NowPlayingArtworkKey.from("track", null))
        assertNull(NowPlayingArtworkKey.from("track", " \n "))
        assertNull(NowPlayingArtworkKey.from("", item.artworkUri))
    }

    private fun changes(next: PlaybackSnapshot): NowPlayingChanges =
        nowPlayingProjection(next, item).changesFrom(nowPlayingProjection(snapshot, item))
}
