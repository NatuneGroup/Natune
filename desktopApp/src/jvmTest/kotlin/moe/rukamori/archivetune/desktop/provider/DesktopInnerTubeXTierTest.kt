package moe.rukamori.archivetune.desktop.provider

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class DesktopInnerTubeXTierTest {
    private val http = HttpClient(CIO)

    @AfterTest
    fun closeClient() = http.close()

    private class FakeExtractor(private val stream: ExtractedStream?) : StreamExtractor {
        var hints: ContentHints? = null
        var quality: AudioQuality? = null

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            this.hints = hints
            quality = audioQuality
            return stream
        }
    }

    private fun stream(url: String = "https://rr1.googlevideo.com/videoplayback?id=1", boundedRange: Boolean = false) =
        ExtractedStream(
            videoId = "dQw4w9WgXcQ",
            audioUrl = url,
            headers = mapOf("User-Agent" to "client-agent"),
            loudnessDb = null,
            expiresAt = null,
            contentLengthBytes = 4_000L,
            itag = 251,
            mimeType = "audio/webm; codecs=\"opus\"",
            codecs = "opus",
            bitrate = 160_000,
            sampleRate = 48_000,
            clientName = "ANDROID_VR",
            profileId = "android_vr",
            requireBoundedRange = boundedRange,
            rangeChunkSizeBytes = 0L,
        )

    @Test
    fun plainStreamBecomesAProgressiveYouTubeStreamWithItsHeaders() = runBlocking {
        val extractor = FakeExtractor(stream())
        val audio = DesktopInnerTubeXTier(http, extractor).resolve("dQw4w9WgXcQ", DesktopYouTubeAudioQuality.HIGHEST, trusted = true)
        val progressive = assertIs<PreparedAudio.Progressive>(audio)
        assertEquals(AudioSourceType.YOUTUBE, progressive.stream.source)
        assertEquals("audio/webm", progressive.stream.mimeType)
        assertEquals("opus", progressive.stream.codecs)
        assertEquals(48_000, progressive.stream.sampleRate)
        assertTrue(progressive.stream.trustedDirectId)
        assertEquals("client-agent", progressive.stream.headers.reveal()["User-Agent"])
        assertEquals(AudioQuality.HIGH, extractor.quality)
        val hints = requireNotNull(extractor.hints)
        assertEquals(false, hints.allowHls)
    }

    @Test
    fun aRangeOnlyStreamIsAcceptedAndHlsIsRefused() {
        runBlocking {
            // A client that requires a bounded range is a client this host can read: the engine leases the media
            // through HttpRangeSource, which only ever asks for a bounded range, so the range-only refusal is gone
            // (see the class KDoc). HLS is still refused, because a leased source refuses FFmpeg's child requests.
            val rangeOnly =
                DesktopInnerTubeXTier(http, FakeExtractor(stream(boundedRange = true)))
                    .resolve("dQw4w9WgXcQ", DesktopYouTubeAudioQuality.AUTO, trusted = true)
            assertIs<PreparedAudio.Progressive>(rangeOnly)
            assertFailsWith<DesktopInnerTubeXException> {
                DesktopInnerTubeXTier(http, FakeExtractor(stream(url = "https://manifest.googlevideo.com/api/manifest/hls_playlist")))
                    .resolve("dQw4w9WgXcQ", DesktopYouTubeAudioQuality.AUTO, trusted = true)
            }
        }
    }

    @Test
    fun noStreamIsNoResult() = runBlocking {
        assertNull(DesktopInnerTubeXTier(http, FakeExtractor(null)).resolve("dQw4w9WgXcQ", DesktopYouTubeAudioQuality.LOW, trusted = false))
    }
}
