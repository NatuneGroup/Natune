package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/**
 * The desktop port against HTTP progressive audio, HLS and DASH, served by [FixtureHttpServer] on
 * the loopback interface. Each stream must report its duration and buffered range, and must seek.
 */
class DesktopPlaybackStreamTest {
    @Test
    fun progressiveHttpWavPlaysAndSeeksWithRangeRequests() {
        withServer { server, port ->
            port.submit(PlaybackCommand.Load(remoteRequest("occ-http-wav", "${server.baseUrl}/ramp-48k.wav"), 0L, true))

            val playing = port.awaitSnapshot("streamed audio past 200 ms") {
                it.isAudioAdvancing && it.positionMs >= 200L
            }
            assertDurationNearFourSeconds(playing)
            assertBufferedAhead(playing)

            port.submit(PlaybackCommand.SeekTo(2_000L))
            val seeked = port.awaitSnapshot("the completed streamed seek") { it.discontinuityGeneration == 1L }
            assertTrue(seeked.positionMs in 2_000L..2_500L, "seek to 2000 ms published ${seeked.positionMs} ms")
            port.awaitSnapshot("streamed playback past the seek target") { it.isAudioAdvancing && it.positionMs >= 2_300L }
        }
    }

    @Test
    fun hlsStreamReportsDurationAndSeeks() {
        withServer { server, port ->
            port.submit(PlaybackCommand.Load(remoteRequest("occ-hls", "${server.baseUrl}/hls/index.m3u8"), 0L, true))

            val playing = port.awaitSnapshot("HLS audio past 200 ms") { it.isAudioAdvancing && it.positionMs >= 200L }
            assertDurationNearFourSeconds(playing)
            assertBufferedAhead(playing)

            port.submit(PlaybackCommand.SeekTo(2_500L))
            val seeked = port.awaitSnapshot("the completed HLS seek") { it.discontinuityGeneration == 1L }
            assertTrue(seeked.positionMs in 2_500L..3_000L, "seek to 2500 ms published ${seeked.positionMs} ms")
        }
    }

    @Test
    fun dashStreamReportsDurationAndSeeks() {
        withServer { server, port ->
            port.submit(PlaybackCommand.Load(remoteRequest("occ-dash", "${server.baseUrl}/dash/manifest.mpd"), 0L, true))

            val playing = port.awaitSnapshot("DASH audio past 200 ms") { it.isAudioAdvancing && it.positionMs >= 200L }
            assertDurationNearFourSeconds(playing)
            assertBufferedAhead(playing)

            port.submit(PlaybackCommand.SeekTo(2_500L))
            val seeked = port.awaitSnapshot("the completed DASH seek") { it.discontinuityGeneration == 1L }
            assertTrue(seeked.positionMs in 2_500L..3_000L, "seek to 2500 ms published ${seeked.positionMs} ms")
        }
    }

    @Test
    fun networkStallFreezesThePositionUntilTheSegmentArrives() {
        withServer { server, port ->
            // Holding the third segment back starves the ring after about two seconds of audio, so the
            // stall starts mid-stream rather than at the first frame.
            server.delayNextResponse("/hls/seg02.ts", STALL_MS)
            port.submit(PlaybackCommand.Load(remoteRequest("occ-stall", "${server.baseUrl}/hls/index.m3u8"), 0L, true))

            val stallStart = port.awaitSnapshot("the start of a network stall") {
                it.isBuffering && it.positionMs > 300L
            }
            val stalled = mutableListOf<PlaybackSnapshot>()
            val deadline = System.nanoTime() + STALL_WAIT_NS
            while (true) {
                val current = assertNotNull(port.snapshots.value)
                if (!current.isBuffering) break
                stalled += current
                assertTrue(System.nanoTime() < deadline, "the stall did not end within $STALL_WAIT_NS ns")
                Thread.sleep(POLL_MS)
            }

            assertTrue(stalled.size >= MIN_STALL_SAMPLES, "only ${stalled.size} snapshots during the stall")
            assertTrue(
                stalled.all { it.positionMs == stallStart.positionMs && !it.isAudioAdvancing },
                "position moved during the stall: ${stalled.map { it.positionMs }.distinct()}",
            )
            port.awaitSnapshot("playback resuming after the stall") {
                it.isAudioAdvancing && it.positionMs > stallStart.positionMs + 300L
            }
        }
    }

    @Test
    fun missingHttpResourceReportsAnOpenFailure() {
        withServer { server, port ->
            FailureRecorder(port).use { failures ->
                port.submit(PlaybackCommand.Load(remoteRequest("occ-404", "${server.baseUrl}/missing.wav"), 0L, true))

                val failure = failures.awaitFirst()

                assertEquals("occ-404", failure.occurrenceId)
                assertEquals(PlaybackFailureReason.OPEN_FAILED, failure.reason)
                assertFalse(failure.message.isBlank())
            }
        }
    }

    private fun assertDurationNearFourSeconds(snapshot: PlaybackSnapshot) {
        val duration = assertNotNull(snapshot.durationMs, "the stream must report its duration")
        assertTrue(duration in MIN_DURATION_MS..MAX_DURATION_MS, "stream duration was $duration ms")
    }

    private fun assertBufferedAhead(snapshot: PlaybackSnapshot) {
        val buffered = assertNotNull(snapshot.bufferedPositionMs, "the stream must report its buffered range")
        assertTrue(buffered >= snapshot.positionMs, "buffered $buffered ms is behind position ${snapshot.positionMs} ms")
    }

    private fun withServer(block: (FixtureHttpServer, PlaybackPort) -> Unit) {
        FixtureHttpServer.start(PlaybackFixtures.root).use { server ->
            val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
            try {
                block(server, port)
            } finally {
                port.close()
            }
        }
    }

    private companion object {
        const val MIN_DURATION_MS = 3_900L
        const val MAX_DURATION_MS = 4_100L
        const val STALL_MS = 3_000L
        const val STALL_WAIT_NS = 20_000_000_000L
        const val MIN_STALL_SAMPLES = 5
        const val POLL_MS = 20L
    }
}
