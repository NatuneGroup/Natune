package moe.rukamori.archivetune.shared.data.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.models.response.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue

/**
 * Live-network smoke test of the exact InnerTubeX calls the iOS resolver and browse transport make. The iOS
 * classes are thin wrappers over these calls; running them on the JVM proves the request shapes, params and the
 * extractor path against real YouTube while the iOS simulator's trust store is broken in this environment.
 *
 * These requests leave the machine, so the tests are opt-in: they run only when the `INNERTUBE_SMOKE`
 * environment variable is `1`, and are otherwise reported as skipped. The `Unit tests` workflow stays
 * authoritative for the offline suites and sets the flag from its `innertube_smoke` manual-dispatch input;
 * locally, run `INNERTUBE_SMOKE=1 ./gradlew :shared-data:jvmTest` to exercise them deliberately.
 */
class JvmInnerTubeXSmokeTest {
    /** A live YouTube run must be asked for; a network outage or a rate limit is not a regression here. */
    @BeforeTest
    fun requireLiveNetworkOptIn() =
        assumeTrue(
            "Live InnerTube smoke test skipped: set INNERTUBE_SMOKE=1 to run it against YouTube.",
            System.getenv(INNERTUBE_SMOKE) == "1",
        )

    private val client =
        HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 30_000L
            }
        }
    private val innerTube =
        InnerTube(
            httpClient = client,
            logger = com.metrolist.innertubex.InnerTubeLogger { event ->
                println("ITX ${event.level} ${event.tag} ${event.message} ${event.details}")
            },
        )

    @Test
    fun searchReturnsSongEntries() = runTest(timeout = 2.minutes) {
        try {
            val response =
                innerTube.search(
                    YouTubeClient.WEB_REMIX,
                    query = "daft punk",
                    params = "EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D",
                ).body<SearchResponse>()
            assertTrue(response.toString().isNotEmpty(), "search returned an empty response")
        } catch (failure: Exception) {
            // Isolate whether the 400 comes from InnerTubeX's request build or the engine layer:
            // the identical body posted bare must return 200 like curl does.
            val manual =
                client.post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260707.12.00","hl":"en","gl":"US"}},"query":"daft punk","params":"EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D"}""",
                    )
                }
            println("PROBE manual-search status=${manual.status} body=${manual.bodyAsText().take(200)}")
            throw failure
        }
    }

    @Test
    fun playerWalkResolvesPlainAudio() = runTest(timeout = 2.minutes) {
        val response = innerTube.player(YouTubeClient.WEB_REMIX, VIDEO_ID).body<PlayerResponse>()
        assertEquals("OK", response.playabilityStatus.status, "playability: ${response.playabilityStatus.reason}")
        val format =
            response.streamingData?.adaptiveFormats.orEmpty().firstOrNull { format ->
                format.isAudio && format.url?.isNotBlank() == true &&
                    format.signatureCipher == null && format.cipher == null
            }
        assertNotNull(format, "no plain-URL audio format in the WEB_REMIX player response")
        println("PLAYER -> itag ${format.itag} ${format.bitrate / 1000} kbps")
    }

    @Test
    fun extractorTierResolvesAudio() = runTest(timeout = 2.minutes) {
        val playerConfigStore =
            RemotePlayerConfigStore(httpClient = client, repository = PlayerConfigRepository.disabled())
        val extractor =
            InnerTubeExtractor(
                configParser = YtConfigParserImpl(client, innerTube, playerConfigStore),
                cipherService = YouTubeCipherService(client, playerConfigStore),
                innerTube = innerTube,
            )
        extractor.prewarm()
        val stream =
            extractor.extract(
                videoId = VIDEO_ID,
                hints =
                    ContentHints(wantVideo = false).withStreamCapabilities(
                        allowHls = false,
                        allowSabr = false,
                        allowBoundedRange = false,
                    ),
                audioQuality = AudioQuality.AUTO,
            )
        assertNotNull(stream, "extractor returned no stream")
        assertTrue(stream.audioUrl.startsWith("https://"), "unexpected audio url")
        println("EXTRACTOR -> ${stream.clientName} ${(stream.bitrate ?: 0) / 1000} kbps")
    }

    private companion object {
        const val VIDEO_ID = "60ItHLz5WEA"
        const val INNERTUBE_SMOKE = "INNERTUBE_SMOKE"
    }
}
