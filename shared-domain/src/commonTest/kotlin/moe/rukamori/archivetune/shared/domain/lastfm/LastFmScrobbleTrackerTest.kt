package moe.rukamori.archivetune.shared.domain.lastfm

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LastFmScrobbleTrackerTest {
    private val play = LastFmPlay(
        id = "occurrence-1",
        artistNames = listOf("Daft Punk"),
        title = "One More Time",
        albumTitle = "Discovery",
        metadataDurationSeconds = 320,
    )

    @Test
    fun anUnknownOrShortTrackIsNeverScrobbled() {
        val timing = LastFmScrobbleTiming()
        assertNull(timing.thresholdMillis(0))
        assertNull(timing.thresholdMillis(timing.minSongDurationSeconds))
        // 50% of a 200 s track is sooner than the 180 s ceiling, so the percentage decides.
        assertEquals(100_000L, requireNotNull(timing.thresholdMillis(200)))
        // A long track stops at the ceiling instead of waiting for the percentage.
        assertEquals(180_000L, requireNotNull(timing.thresholdMillis(1_000)))
    }

    @Test
    fun theScrobbleFiresOnceAtTheThresholdWithTheStartTime() = runTest {
        val nowPlaying = mutableListOf<LastFmTrackMetadata>()
        val scrobbles = mutableListOf<Pair<LastFmTrackMetadata, Long>>()
        val tracker = tracker(nowPlaying, scrobbles)

        tracker.onSongStart(play)
        runCurrent()
        assertEquals(1, nowPlaying.size)
        assertEquals("Daft Punk", nowPlaying.single().artist)

        // 50% of the 320 s track is 160 s.
        advanceTimeBy(159_000)
        runCurrent()
        assertTrue(scrobbles.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, scrobbles.size)
        assertEquals(1_700_000_000L, scrobbles.single().second)
        assertEquals("One More Time", scrobbles.single().first.track)

        // A later timer or a resume must never send the same play twice.
        advanceTimeBy(400_000)
        runCurrent()
        assertEquals(1, scrobbles.size)
    }

    @Test
    fun aPauseCancelsThePendingScrobble() = runTest {
        val scrobbles = mutableListOf<Pair<LastFmTrackMetadata, Long>>()
        val tracker = tracker(mutableListOf(), scrobbles)

        tracker.onSongStart(play)
        runCurrent()
        tracker.onSongPause()
        advanceTimeBy(400_000)
        runCurrent()
        assertTrue(scrobbles.isEmpty())
    }

    @Test
    fun aNewSongCancelsThePreviousPlaysTimer() = runTest {
        val scrobbles = mutableListOf<Pair<LastFmTrackMetadata, Long>>()
        val tracker = tracker(mutableListOf(), scrobbles)

        tracker.onSongStart(play)
        runCurrent()
        tracker.onSongStart(play.copy(id = "occurrence-2", title = "Aerodynamic", metadataDurationSeconds = 200))
        runCurrent()
        advanceTimeBy(400_000)
        runCurrent()
        assertEquals(listOf("Aerodynamic"), scrobbles.map { it.first.track })
    }

    @Test
    fun podcastsAreNeverTracked() = runTest {
        val nowPlaying = mutableListOf<LastFmTrackMetadata>()
        val scrobbles = mutableListOf<Pair<LastFmTrackMetadata, Long>>()
        val tracker = tracker(nowPlaying, scrobbles)

        tracker.onSongStart(play.copy(isPodcast = true))
        advanceTimeBy(400_000)
        runCurrent()
        assertTrue(nowPlaying.isEmpty())
        assertTrue(scrobbles.isEmpty())
    }

    @Test
    fun aResumeRefreshesNowPlayingOnlyOncePerMinute() = runTest {
        var clockNanos = 0L
        val nowPlaying = mutableListOf<LastFmTrackMetadata>()
        val tracker = tracker(nowPlaying, mutableListOf(), MonotonicClock { clockNanos })

        tracker.onSongStart(play)
        runCurrent()
        clockNanos = 5_000_000_000L
        tracker.onSongResume(play)
        runCurrent()
        assertEquals(1, nowPlaying.size)

        clockNanos = 90_000_000_000L
        tracker.onSongResume(play)
        runCurrent()
        assertEquals(2, nowPlaying.size)
    }

    @Test
    fun nowPlayingOffLeavesTheScrobbleRuleAlone() = runTest {
        val nowPlaying = mutableListOf<LastFmTrackMetadata>()
        val scrobbles = mutableListOf<Pair<LastFmTrackMetadata, Long>>()
        val tracker = tracker(nowPlaying, scrobbles)
        tracker.useNowPlaying = false

        tracker.onSongStart(play)
        runCurrent()
        assertTrue(nowPlaying.isEmpty())

        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(1, scrobbles.size)
    }

    private fun TestScope.tracker(
        nowPlaying: MutableList<LastFmTrackMetadata>,
        scrobbles: MutableList<Pair<LastFmTrackMetadata, Long>>,
        clock: MonotonicClock = MonotonicClock { 0L },
    ) = LastFmScrobbleTracker(
        scope = backgroundScope,
        wallClockSeconds = { 1_700_000_000L },
        onNowPlaying = { nowPlaying += it },
        onScrobblePoint = { track, startedAt -> scrobbles += track to startedAt },
        clock = clock,
    )
}
