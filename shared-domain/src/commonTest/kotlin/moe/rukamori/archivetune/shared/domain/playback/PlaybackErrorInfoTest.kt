package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackErrorInfoTest {
    private fun failure(
        reason: PlaybackFailureReason,
        message: String = "",
        httpStatus: Int? = null,
        causes: List<String> = emptyList(),
    ) = PlaybackFailure("occ-1", reason, message, httpStatus, causes)

    @Test
    fun unresolvedSongIsNoStream() {
        val info = failure(PlaybackFailureReason.SOURCE_UNAVAILABLE, "no connected source resolved youtube").toPlaybackErrorInfo()
        assertEquals(PlaybackErrorKind.NO_STREAM, info.kind)
        assertEquals("SOURCE_UNAVAILABLE", info.code)
    }

    @Test
    fun goneStatusFromEngineTextIsNoStream() {
        val info = failure(PlaybackFailureReason.OPEN_FAILED, "Server returned 403 Forbidden (access denied)").toPlaybackErrorInfo()
        assertEquals(PlaybackErrorKind.NO_STREAM, info.kind)
        assertEquals(403, info.httpStatus)
    }

    @Test
    fun otherStatusIsHttp() {
        assertEquals(PlaybackErrorKind.HTTP, failure(PlaybackFailureReason.OPEN_FAILED, httpStatus = 500).toPlaybackErrorInfo().kind)
    }

    @Test
    fun timeoutWinsOverNetwork() {
        val causes = listOf("SocketTimeoutException: connect timed out", "ConnectException: refused")
        assertEquals(PlaybackErrorKind.TIMEOUT, failure(PlaybackFailureReason.SOURCE_UNAVAILABLE, causes = causes).toPlaybackErrorInfo().kind)
    }

    @Test
    fun unresolvedHostIsNoInternet() {
        val causes = listOf("UnknownHostException: music.youtube.com")
        assertEquals(PlaybackErrorKind.NO_INTERNET, failure(PlaybackFailureReason.SOURCE_UNAVAILABLE, causes = causes).toPlaybackErrorInfo().kind)
    }

    @Test
    fun decodeFailureIsSourceError() {
        assertEquals(PlaybackErrorKind.MALFORMED_STREAM, failure(PlaybackFailureReason.DECODE_FAILED).toPlaybackErrorInfo().kind)
    }

    @Test
    fun outputFailureIsUnknown() {
        assertEquals(PlaybackErrorKind.UNKNOWN, failure(PlaybackFailureReason.OUTPUT_FAILED, "device lost").toPlaybackErrorInfo().kind)
    }

    @Test
    fun causeChainStopsAtTheAndroidDepth() {
        var error: Throwable = IllegalStateException("root")
        repeat(10) { error = RuntimeException("level $it", error) }
        val causes = error.playbackCauses()
        assertEquals(PLAYBACK_ERROR_CAUSE_DEPTH, causes.size)
        assertEquals("RuntimeException: level 9", causes.first())
    }
}
