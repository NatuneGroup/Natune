package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState

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
        assertEquals("YouTube", stats.sourceLabel)
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

    @Test fun sourceLabelsCoverEveryKnownProviderAndPassUnknownIdsThrough() {
        assertEquals("Local", nerdSourceLabel(ContentProvider.LOCAL.id))
        assertEquals("YouTube", nerdSourceLabel(ContentProvider.YOUTUBE.id))
        assertEquals("Telegram", nerdSourceLabel(ContentProvider.TELEGRAM.id))
        assertEquals("Spotify (streaming)", nerdSourceLabel(ContentProvider.SPOTIFY_STREAMING.id))
        assertEquals("upstream-test", nerdSourceLabel("upstream-test"))
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
