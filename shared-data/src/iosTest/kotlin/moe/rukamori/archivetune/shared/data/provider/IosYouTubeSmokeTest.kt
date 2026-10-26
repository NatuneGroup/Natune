package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.TrackMatching
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier

/**
 * Live-network smoke test: drives the real InnerTubeX catalogue and stream tiers exactly as the playback port
 * does, over the same Darwin engine the session graph injects. It needs network access to YouTube.
 */
class IosYouTubeSmokeTest {
    private val http = HttpClient(Darwin)
    private val transport = IosYouTubeBrowseTransport(http)
    private val resolver = IosYouTubeResolver(http, transport, preferences = { null }, personalScope = { null })

    @Test
    fun httpsReachabilityProbe() = runTest(timeout = 2.minutes) {
        for (url in listOf("https://example.com/", "https://music.youtube.com/", "https://www.googleapis.com/")) {
            try {
                val body = http.get(url).bodyAsText()
                println("PROBE $url -> ${body.length} bytes")
            } catch (failure: Exception) {
                println("PROBE $url -> FAIL ${failure.message?.take(120)}")
            }
        }
    }

    @Test
    fun searchReturnsEntries() = runTest(timeout = 2.minutes) {
        val page = transport.page(YouTubeBrowse.Search("daft punk", ContentSearchFilter.SONGS), continuation = null)
        assertTrue(page.entries.isNotEmpty(), "YouTube search returned no entries")
    }

    @Test
    fun primaryTierResolvesPlainAudio() = runTest(timeout = 2.minutes) {
        val request = request()
        val audio = resolver.primary(request) ?: fail("primary tier returned no stream")
        val stream = (audio as? PreparedAudio.Progressive)?.stream ?: fail("primary returned a non-progressive stream")
        assertTrue(stream.uri.startsWith("https://"), "unexpected stream uri: ${stream.uri.take(60)}")
        println("PRIMARY -> ${stream.label}")
    }

    @Test
    fun innerTubeXTierResolvesAudio() = runTest(timeout = 2.minutes) {
        val audio = resolver.fallback(YouTubeFallbackTier.INNERTUBE_X, request()) ?: fail("InnerTubeX returned no stream")
        val stream = (audio as? PreparedAudio.Progressive)?.stream ?: fail("InnerTubeX returned a non-progressive stream")
        assertTrue(stream.uri.startsWith("https://"), "unexpected stream uri: ${stream.uri.take(60)}")
        println("INNERTUBE_X -> ${stream.label}")
    }

    private fun request(): AudioResolveRequest =
        AudioResolveRequest(
            mediaId = VIDEO_ID,
            target = TrackMatching.Target(title = "Faded", artists = listOf("Alan Walker"), album = null, durationMs = null),
            directIds = mapOf(AudioSourceType.YOUTUBE to VIDEO_ID),
        )

    private fun fail(message: String): Nothing = kotlin.test.fail(message)

    private companion object {
        // Alan Walker "Faded" — a long-lived YouTube id used by upstream InnerTube tests as well.
        const val VIDEO_ID = "60ItHLz5WEA"
    }
}
