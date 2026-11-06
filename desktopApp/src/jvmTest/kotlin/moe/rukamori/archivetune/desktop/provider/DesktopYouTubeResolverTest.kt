package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.settings.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerStreamClientKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.TrackMatching

class DesktopYouTubeResolverTest {
    private val videoId = "dQw4w9WgXcQ"

    private fun request() = AudioResolveRequest(
        mediaId = videoId,
        target = TrackMatching.Target("Song", listOf("Artist"), null, 180_000),
        directIds = mapOf(AudioSourceType.YOUTUBE to videoId),
    )

    private fun playerBody(
        status: String = "OK",
        reason: String? = null,
        formats: String = CIPHERED_FORMAT,
    ): String {
        val reasonPart = reason?.let { ",\"reason\":\"$it\"" }.orEmpty()
        val adaptive = formats.takeIf(String::isNotBlank)?.let { ",$it" }.orEmpty()
        return "{\"playabilityStatus\":{\"status\":\"$status\"$reasonPart}," +
            "\"videoDetails\":{\"title\":\"Song\",\"author\":\"Artist\",\"lengthSeconds\":\"180\"}," +
            "\"streamingData\":{\"adaptiveFormats\":[$adaptive]}}"
    }

    @Test
    fun manualClientCarriesThePlayerTimestampAndTheCipheredFormatIsDeciphered() = runBlocking<Unit> {
        val bodies = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            if (call.url.encodedPath.endsWith("/visitor")) return@MockEngine respond("{}")
            val client = call.headers["X-YouTube-Client-Name"].orEmpty()
            calls += client
            bodies += (call.body as? TextContent)?.text.orEmpty()
            respond(
                playerBody(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val cipher = RecordingCipher()
        val preferences = PreferenceSnapshot.EMPTY.toEditor().apply {
            this[AutoChoosePlaybackClientKey] = false
            this[PlayerStreamClientKey] = "ANDROID_MUSIC"
        }.snapshot()
        try {
            val resolver = DesktopYouTubeResolver(http, preferences = { preferences }, cipher = cipher)
            val prepared = assertIs<PreparedAudio.Progressive>(resolver.primary(request()))
            assertEquals(TRANSFORMED_URL, prepared.stream.uri)
            assertEquals(1_800_000_000_000L, prepared.stream.expiresAtEpochMs)
            assertEquals("audio/mp4", prepared.stream.mimeType)
            assertTrue(bodies.isNotEmpty())
            assertTrue(bodies.first().contains("\"signatureTimestamp\":12345"))
            assertTrue(bodies.first().contains("\"clientName\":\"ANDROID_MUSIC\""))
            assertEquals(1, cipher.timestamps)
            assertEquals(1, cipher.ciphertexts)
        } finally {
            http.close()
        }
    }

    @Test
    fun withoutAPoTokenTheWalkLeadsWithTheTokenFreeClients() {
        val anonymous = streamClientOrder(DesktopYouTubeClients.MAIN_CLIENT, null, ageGateAllowed = false)
        assertEquals(
            listOf("ANDROID_VR", "VISIONOS", "IOS"),
            anonymous.take(3).map { it.clientName },
        )
        assertTrue(anonymous.indexOf(DesktopYouTubeClients.MAIN_CLIENT) > 2)

        val tokened = streamClientOrder(
            DesktopYouTubeClients.MAIN_CLIENT,
            null,
            ageGateAllowed = false,
            hasPoToken = true,
        )
        assertEquals(DesktopYouTubeClients.MAIN_CLIENT, tokened.first())

        val manual = streamClientOrder(
            DesktopYouTubeClients.MAIN_CLIENT,
            null,
            ageGateAllowed = false,
            manualClient = true,
        )
        assertEquals(DesktopYouTubeClients.MAIN_CLIENT, manual.first())
    }

    @Test
    fun aBotCheckedWalkNamesTheCheckAndTheClientsItTried() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { call ->
            if (call.url.encodedPath.endsWith("/visitor")) return@MockEngine respond("{}")
            respond(
                playerBody(status = "LOGIN_REQUIRED", reason = "Sign in to confirm you're not a bot", formats = ""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            val resolver = DesktopYouTubeResolver(http, preferences = { null }, cipher = RecordingCipher())
            val failure = assertFailsWith<DesktopYouTubeBotDetectionException> { resolver.primary(request()) }
            assertTrue(failure.message.orEmpty().contains("bot check"))
            assertTrue(failure.message.orEmpty().contains("ANDROID_VR"))
            assertTrue(failure.message.orEmpty().contains("WEB_REMIX"))
        } finally {
            http.close()
        }
    }

    @Test
    fun aGooglevideoExpiryParameterBecomesTheLocatorWindow() {
        assertEquals(1_800_000_000_000L, youTubeStreamExpiryMs("https://cdn.invalid/v?expire=1800000000&n=x"))
        assertEquals(null, youTubeStreamExpiryMs("https://cdn.invalid/v?n=x"))
        assertEquals(null, youTubeStreamExpiryMs("not a url"))
    }

    @Test
    fun headerFreeResultsNameTheMintingClientAgent() {
        val stream = DesktopAudioFormat(
            itag = 140,
            url = "https://cdn.invalid/v?expire=1800000000",
            signatureCipher = null,
            cipher = null,
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            bitrate = 129_000,
            audioSampleRate = 44_100,
            contentLength = 1_000,
            isDefaultAudioTrack = true,
        ).toDirectStream(
            uri = "https://cdn.invalid/v?expire=1800000000",
            label = "YouTube Web 129 kbps",
            title = "Song",
            author = "Artist",
            durationMs = 180_000L,
            trusted = true,
            userAgent = "fixture-agent",
        )
        assertEquals(listOf("User-Agent"), stream.headers.names)
        assertNotNull(stream.expiresAtEpochMs)
    }

    private class RecordingCipher : DesktopYouTubeStreamCipher {
        var timestamps = 0
        var ciphertexts = 0

        override suspend fun signatureTimestamp(videoId: String): Int {
            timestamps++
            return 12345
        }

        override suspend fun playableUrl(
            videoId: String,
            url: String?,
            signatureCipher: String?,
            legacyCipher: String?,
        ): String? {
            ciphertexts++
            if (signatureCipher == null && legacyCipher == null && url == null) return null
            return TRANSFORMED_URL
        }
    }

    private companion object {
        val CIPHERED_FORMAT =
            "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":129000," +
                "\"audioSampleRate\":\"44100\",\"contentLength\":\"1000\"," +
                "\"signatureCipher\":\"s=abc&sp=sig&url=https%3A%2F%2Fcdn.invalid%2Fstream%3F" +
                "expire%3D1800000000%26n%3Dabc\"}"
        const val TRANSFORMED_URL = "https://cdn.invalid/stream?expire=1800000000&n=transformed&sig=deciphered"
    }
}
