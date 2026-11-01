package moe.rukamori.archivetune.shared.data.playback

import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnginePlaybackMapperTest {
    private val request = PlaybackRequest(
        occurrenceId = "occ-1",
        mediaId = "media-1",
        locator = PlaybackLocator.LocalFile("/music/ramp.wav"),
    )

    @Test
    fun playingStateReportsPositionDurationAndBufferedRange() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val snapshot = assertNotNull(
            mapper.observe(
                state(positionUs = 1_250_000L, durationUs = 4_000_000L, bufferedUs = 2_000_000L),
                sampleMonotonicNanos = 10L,
            ),
        )

        assertEquals("occ-1", snapshot.occurrenceId)
        assertEquals("media-1", snapshot.mediaId)
        assertEquals(1L, snapshot.playbackGeneration)
        assertEquals(0L, snapshot.discontinuityGeneration)
        assertEquals(1_250L, snapshot.positionMs)
        assertEquals(4_000L, snapshot.durationMs)
        assertEquals(2_000L, snapshot.bufferedPositionMs)
        assertTrue(snapshot.isAudioAdvancing)
        assertTrue(snapshot.playWhenReady)
        assertTrue(snapshot.isSeekable)
        assertEquals(PlaybackOutputRoute.LOCAL, snapshot.outputRoute)
    }

    @Test
    fun bufferingHoldsAdvancementEvenWhenTheEngineStillReportsIt() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val snapshot = assertNotNull(
            mapper.observe(state(flags = PLAYING or FLAG_BUFFERING), sampleMonotonicNanos = 1L),
        )

        assertTrue(snapshot.isBuffering)
        assertFalse(snapshot.isAudioAdvancing)
    }

    @Test
    fun eachCompletedSeekCountsOnceAcrossRepeatedReads() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val beforeSeek = assertNotNull(mapper.observe(state(seekGeneration = 0L), 1L))
        assertEquals(0L, beforeSeek.discontinuityGeneration)

        val twoSeeks = assertNotNull(mapper.observe(state(seekGeneration = 2L), 2L))
        assertEquals(2L, twoSeeks.discontinuityGeneration)

        val repeated = assertNotNull(mapper.observe(state(seekGeneration = 2L), 3L))
        assertEquals(2L, repeated.discontinuityGeneration)
    }

    @Test
    fun readsFromAnEarlierLoadAreDropped() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 5L)

        assertNull(mapper.observe(state(load = 4L, positionUs = 9_000_000L), sampleMonotonicNanos = 1L))
        assertNotNull(mapper.observe(state(load = 5L), sampleMonotonicNanos = 2L))
    }

    @Test
    fun newRequestAdvancesPlaybackGenerationAndRestartsDiscontinuities() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)
        mapper.observe(state(load = 1L, seekGeneration = 3L), sampleMonotonicNanos = 1L)

        val next = PlaybackRequest("occ-2", "media-2", PlaybackLocator.Remote("https://example.test/a.m3u8"))
        mapper.beginRequest(next, engineLoadGeneration = 2L)
        val snapshot = assertNotNull(mapper.observe(state(load = 2L), sampleMonotonicNanos = 2L))

        assertEquals("occ-2", snapshot.occurrenceId)
        assertEquals(2L, snapshot.playbackGeneration)
        assertEquals(0L, snapshot.discontinuityGeneration)
    }

    @Test
    fun unknownDurationAndBufferedRangeStayNull() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val snapshot = assertNotNull(
            mapper.observe(
                state(flags = FLAG_HAS_SOURCE or FLAG_ADVANCING, durationUs = -1L, bufferedUs = -1L),
                sampleMonotonicNanos = 1L,
            ),
        )

        assertNull(snapshot.durationMs)
        assertNull(snapshot.bufferedPositionMs)
        assertFalse(snapshot.isSeekable)
    }

    @Test
    fun aFailureIsReportedOnceForTheCurrentLoad() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val failed = state(errorCode = EnginePlaybackState.ERROR_OPEN, errorSequence = 1L)
        assertEquals(
            PlaybackFailure("occ-1", PlaybackFailureReason.OPEN_FAILED, "open failed: No such file"),
            mapper.failure(failed) { "open failed: No such file" },
        )
        assertNull(mapper.failure(failed) { "open failed: No such file" })
    }

    @Test
    fun failureRetainsTheQueueAttemptEvenWhenTheSameOccurrenceWasReloaded() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request.copy(attemptId = 7L), engineLoadGeneration = 1L)
        val failed = state(errorCode = EnginePlaybackState.ERROR_OPEN, errorSequence = 1L)
        assertEquals(7L, assertNotNull(mapper.failure(failed) { "fixture" }).attemptId)
        mapper.beginRequest(request.copy(attemptId = 8L), engineLoadGeneration = 2L)
        assertNull(mapper.failure(failed) { "stale" })
        assertEquals(8L, assertNotNull(mapper.failure(
            state(load = 2L, errorCode = EnginePlaybackState.ERROR_DECODE),
        ) { "fixture" }).attemptId)
    }

    @Test
    fun otherLoadsAndCleanStatesProduceNoFailure() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 2L)

        assertNull(mapper.failure(state(load = 2L)) { "no error" })
        assertNull(mapper.failure(state(load = 1L, errorCode = EnginePlaybackState.ERROR_DECODE)) { "stale" })
    }

    @Test
    fun messageIsOnlyEvaluatedWhenAFailureIsReported() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)
        var evaluations = 0
        val message: () -> String = {
            evaluations++
            "decode failed"
        }

        mapper.failure(state(), message)
        mapper.failure(state(load = 0L, errorCode = EnginePlaybackState.ERROR_DECODE), message)
        assertEquals(0, evaluations)

        mapper.failure(state(errorCode = EnginePlaybackState.ERROR_DECODE), message)
        mapper.failure(state(errorCode = EnginePlaybackState.ERROR_DECODE), message)
        assertEquals(1, evaluations)
    }

    @Test
    fun decodeAndOutputErrorsMapToTheirReasons() {
        val decode = EnginePlaybackMapper()
        decode.beginRequest(request, engineLoadGeneration = 1L)
        assertEquals(
            PlaybackFailureReason.DECODE_FAILED,
            decode.failure(state(errorCode = EnginePlaybackState.ERROR_DECODE)) { "decode" }.assertReason(),
        )

        val output = EnginePlaybackMapper()
        output.beginRequest(request, engineLoadGeneration = 1L)
        assertEquals(
            PlaybackFailureReason.OUTPUT_FAILED,
            output.failure(state(errorCode = EnginePlaybackState.ERROR_OUTPUT)) { "output" }.assertReason(),
        )
    }

    @Test
    fun stoppingKeepsTheLastPositionAndStopsAdvancement() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)
        mapper.observe(state(positionUs = 3_000_000L), sampleMonotonicNanos = 1L)

        val stopped = assertNotNull(mapper.stopped(sampleMonotonicNanos = 99L))

        assertEquals(3_000L, stopped.positionMs)
        assertEquals(99L, stopped.sampleMonotonicNanos)
        assertFalse(stopped.playWhenReady)
        assertFalse(stopped.isAudioAdvancing)
        assertNull(mapper.observe(state(positionUs = 4_000_000L), sampleMonotonicNanos = 100L))
        assertNull(mapper.stopped(sampleMonotonicNanos = 101L))
    }

    @Test
    fun outputLatencyStaysSeparateFromMediaPositionsAndSurvivesStop() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)
        val snapshot = assertNotNull(mapper.observe(state(positionUs = 3_000_000L), 1L, outputLatencyMs = 2_000L))
        assertEquals(3_000L, snapshot.positionMs)
        assertEquals(2_000L, snapshot.outputLatencyMs)
        assertEquals(2_000L, assertNotNull(mapper.stopped(2L)).outputLatencyMs)
    }

    @Test
    fun missingLatencyDoesNotReuseThePreviousRoutesEstimate() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)
        mapper.observe(state(), 1L, outputLatencyMs = 2_000L)
        assertNull(assertNotNull(mapper.observe(state(), 2L)).outputLatencyMs)
    }

    @Test
    fun speedChangesAreReportedFromTheEngine() {
        val mapper = EnginePlaybackMapper()
        mapper.beginRequest(request, engineLoadGeneration = 1L)

        val snapshot = assertNotNull(mapper.observe(state(speed = 2f), sampleMonotonicNanos = 1L))

        assertEquals(2f, snapshot.playbackSpeed)
    }

    @Test
    fun nothingIsPublishedBeforeAnyRequest() {
        val mapper = EnginePlaybackMapper()

        assertNull(mapper.observe(state(), sampleMonotonicNanos = 1L))
        assertNull(mapper.failure(state(errorCode = EnginePlaybackState.ERROR_OPEN)) { "open" })
    }

    private fun PlaybackFailure?.assertReason(): PlaybackFailureReason = assertNotNull(this).reason

    private fun state(
        load: Long = 1L,
        flags: Int = PLAYING,
        errorCode: Int = EnginePlaybackState.ERROR_NONE,
        seekGeneration: Long = 0L,
        errorSequence: Long = 0L,
        positionUs: Long = 0L,
        durationUs: Long = 4_000_000L,
        bufferedUs: Long = 0L,
        speed: Float = 1f,
    ): EnginePlaybackState = EnginePlaybackState(
        flags = flags,
        errorCode = errorCode,
        loadGeneration = load,
        seekGeneration = seekGeneration,
        errorSequence = errorSequence,
        positionUs = positionUs,
        durationUs = durationUs,
        bufferedUs = bufferedUs,
        playbackSpeed = speed,
    )

    private companion object {
        const val FLAG_HAS_SOURCE = EnginePlaybackState.FLAG_HAS_SOURCE
        const val FLAG_PLAY_WHEN_READY = EnginePlaybackState.FLAG_PLAY_WHEN_READY
        const val FLAG_ADVANCING = EnginePlaybackState.FLAG_ADVANCING
        const val FLAG_BUFFERING = EnginePlaybackState.FLAG_BUFFERING
        const val FLAG_SEEKABLE = EnginePlaybackState.FLAG_SEEKABLE
        const val PLAYING = FLAG_HAS_SOURCE or FLAG_PLAY_WHEN_READY or FLAG_ADVANCING or FLAG_SEEKABLE
    }
}
