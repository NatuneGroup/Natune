package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_source_local
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_source_spotify_streaming
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_source_telegram
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_source_youtube

class DeveloperPanelModelsTest {

    @Test fun anAbsentSnapshotReportsNoTrack() {
        val stats = nerdStatsStateOf(null, null, null, null)
        assertEquals(NerdPlaybackState.IDLE, stats.state)
        assertNull(stats.mediaId)
        assertNull(stats.bufferedPositionMs)
        assertNull(stats.playbackSpeed)
        assertNull(stats.format)
    }

    @Test fun thePlayingTrackBindsItsSnapshotFields() {
        val stats = nerdStatsStateOf(
            snapshot(positionMs = 42_000L, bufferedPositionMs = 90_000L, speed = 1.25f),
            item(),
            null,
            43_000L,
        )
        assertEquals("abcdefghijk", stats.mediaId)
        assertEquals("A song", stats.title)
        assertEquals("An artist", stats.artists)
        assertEquals(ContentProvider.YOUTUBE.id, stats.sourceProviderId)
        assertEquals(43_000L, stats.positionMs)
        assertEquals(90_000L, stats.bufferedPositionMs)
        assertEquals(1.25f, stats.playbackSpeed)
        assertEquals(NerdPlaybackState.READY, stats.state)
    }

    @Test fun endedAndBufferingStatesWinOverReady() {
        assertEquals(NerdPlaybackState.ENDED, nerdStatsStateOf(snapshot(ended = true), item(), null, null).state)
        assertEquals(NerdPlaybackState.BUFFERING, nerdStatsStateOf(snapshot(buffering = true), item(), null, null).state)
        // Buffering outranks a live advance flag, matching the player's own state order.
        assertEquals(
            NerdPlaybackState.BUFFERING,
            nerdStatsStateOf(snapshot(buffering = true, advancing = true), item(), null, null).state,
        )
    }

    @Test fun subtitleFallsInWhenArtistsAreMissing() {
        val stats = nerdStatsStateOf(snapshot(), item().copy(artists = emptyList(), subtitle = "A show"), null, null)
        assertEquals("A show", stats.artists)
    }

    @Test fun sourceLabelsCoverKnownProvidersAndPassUnknownIdsThrough() {
        assertEquals(Res.string.m6_dev_source_local, nerdSourceLabel(ContentProvider.LOCAL.id))
        assertEquals(Res.string.m6_dev_source_youtube, nerdSourceLabel(ContentProvider.YOUTUBE.id))
        assertEquals(Res.string.m6_dev_source_telegram, nerdSourceLabel(ContentProvider.TELEGRAM.id))
        assertEquals(
            Res.string.m6_dev_source_spotify_streaming,
            nerdSourceLabel(ContentProvider.SPOTIFY_STREAMING.id),
        )
        assertNull(nerdSourceLabel("upstream-test"))
    }

    @Test fun bufferPercentNeedsBothFields() {
        assertNull(nerdStatsBufferPercent(null, 180_000L))
        assertNull(nerdStatsBufferPercent(90_000L, null))
        assertNull(nerdStatsBufferPercent(90_000L, 0L))
        assertEquals(50, nerdStatsBufferPercent(90_000L, 180_000L))
        // A buffered edge past the reported duration clamps at 100 rather than overreporting.
        assertEquals(100, nerdStatsBufferPercent(200_000L, 180_000L))
        assertEquals(0, nerdStatsBufferPercent(0L, 180_000L))
    }

    @Test fun theAcousticTargetReadsThePlayingOccurrence() {
        val queue = QueueState(
            revision = 1,
            entries = listOf(ContentOccurrence("occ-1", item()), ContentOccurrence("occ-2", item())),
            currentOccurrenceId = "occ-2",
            playOrder = listOf("occ-1", "occ-2"),
        )
        assertNull(acousticTargetOf(null, queue))
        val target = acousticTargetOf(snapshot(occurrenceId = "occ-2"), queue)
        assertEquals("abcdefghijk", target?.mediaId)
        assertEquals(ContentProvider.YOUTUBE.id, target?.provider)
        assertEquals("A song", target?.title)
        assertEquals("An artist", target?.artists)
        assertNull(acousticTargetOf(snapshot(occurrenceId = "occ-3"), queue))
    }

    @Test fun theAcousticTargetCarriesTheResolvedSource() {
        val queue = QueueState(
            revision = 1,
            entries = listOf(ContentOccurrence("occ-1", item())),
            currentOccurrenceId = "occ-1",
            playOrder = listOf("occ-1"),
        )
        val request = PlaybackRequest(
            occurrenceId = "occ-1",
            mediaId = "abcdefghijk",
            locator = PlaybackLocator.Remote("https://audio.example/track", source = AudioSourceType.TIDAL),
        )
        val target = acousticTargetOf(snapshot(), queue, request)
        assertEquals(AudioSourceType.TIDAL.name, target?.source)
        // A resolved request belonging to another occurrence must not name this target's source.
        assertNull(acousticTargetOf(snapshot(), queue, request.copy(occurrenceId = "occ-2"))?.source)
    }

    private fun item() = ContentItem(
        ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "abcdefghijk"),
        title = "A song",
        artists = listOf(ContentCredit("An artist")),
    )

    private fun snapshot(
        occurrenceId: String = "occ-1",
        positionMs: Long = 0L,
        bufferedPositionMs: Long? = null,
        durationMs: Long? = 180_000L,
        speed: Float = 1f,
        buffering: Boolean = false,
        advancing: Boolean = true,
        ended: Boolean = false,
    ) = PlaybackSnapshot(
        occurrenceId = occurrenceId,
        mediaId = "abcdefghijk",
        playbackGeneration = 1L,
        discontinuityGeneration = 1L,
        positionMs = positionMs,
        sampleMonotonicNanos = 0L,
        durationMs = durationMs,
        isSeekable = true,
        playbackSpeed = speed,
        playWhenReady = advancing,
        isAudioAdvancing = advancing,
        isBuffering = buffering,
        outputRoute = PlaybackOutputRoute.LOCAL,
        bufferedPositionMs = bufferedPositionMs,
        isEnded = ended,
    )
}
