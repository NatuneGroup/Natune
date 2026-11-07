package moe.rukamori.archivetune.desktop.provider

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.strategy.ClientLifecycle
import com.metrolist.innertubex.extraction.strategy.ClientSelectionMode
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.settings.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackClientChoice
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
        return "{\"playabilityStatus\":{\"status\":\"$status\"$reasonPart}," +
            "\"videoDetails\":{\"title\":\"Song\",\"author\":\"Artist\",\"lengthSeconds\":\"180\"}," +
            "\"streamingData\":{\"adaptiveFormats\":[$formats]}}"
    }

    @Test
    fun manualClientCarriesThePlayerTimestampAndTheCipheredFormatIsDeciphered() = runBlocking<Unit> {
        val bodies = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            // The visitor fetch is a GET to sw.js_data, not a player request; only player posts are recorded.
            if (!call.url.encodedPath.endsWith("/player")) return@MockEngine respond("{}")
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
            val resolver = DesktopYouTubeResolver(
                http,
                preferences = { preferences },
                cipher = cipher,
                // The InnerTubeX tier is the resolver's first answer; this test is about the hand-rolled walk behind it.
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = StubStreamExtractor()),
            )
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
    fun withoutAPoTokenTheWalkLeadsWithTheRangeCapableTokenFreeClient() {
        val anonymous = streamClientOrder(DesktopYouTubeClients.MAIN_CLIENT, null, ageGateAllowed = false)
        assertEquals(
            listOf("VISIONOS", "ANDROID_VR", "IOS"),
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
            if (!call.url.encodedPath.endsWith("/player")) return@MockEngine respond("{}")
            respond(
                playerBody(status = "LOGIN_REQUIRED", reason = "Sign in to confirm you're not a bot", formats = ""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            val resolver = DesktopYouTubeResolver(
                http,
                preferences = { null },
                cipher = RecordingCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = StubStreamExtractor()),
            )
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

    /**
     * The resolver's order: InnerTubeX answers first, and the hand-rolled client walk runs only when it did not. The
     * mocked client records every player request, so "the walk did not run" is observable rather than assumed.
     */
    @Test
    fun innerTubeXAnswersBeforeTheClientWalkAndTheWalkIsTheFallback() = runBlocking<Unit> {
        val playerCalls = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            if (!call.url.encodedPath.endsWith("/player")) return@MockEngine respond("{}")
            playerCalls += call.headers["X-YouTube-Client-Name"].orEmpty()
            respond(playerBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val answered = StubStreamExtractor(answer = extractedStream())
            val first = DesktopYouTubeResolver(
                http,
                preferences = { null },
                cipher = RecordingCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = answered),
            )
            val prepared = assertIs<PreparedAudio.Progressive>(first.primary(request()))
            assertEquals(ITX_URL, prepared.stream.uri)
            // The library reported no Instant, so the URL's own `expire` window is what the locator carries.
            assertEquals(1_800_000_000_000L, prepared.stream.expiresAtEpochMs)
            assertEquals("YouTube InnerTubeX WEB_REMIX 128 kbps", prepared.stream.label)
            assertEquals(listOf("User-Agent"), prepared.stream.headers.names)
            assertEquals(1, answered.extractions)
            assertTrue(playerCalls.isEmpty(), "the client walk must not run once InnerTubeX answered: $playerCalls")

            // No stream from the library: the walk behind it answers, and it is the tier that is logged as failing.
            val silent = StubStreamExtractor(answer = null)
            val lines = mutableListOf<String>()
            val second = DesktopYouTubeResolver(
                http,
                preferences = { null },
                cipher = RecordingCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = silent),
                log = { lines += it },
            )
            val walked = assertIs<PreparedAudio.Progressive>(second.primary(request()))
            assertEquals(TRANSFORMED_URL, walked.stream.uri)
            assertEquals(1, silent.extractions)
            assertTrue(playerCalls.isNotEmpty(), "the hand-rolled walk must run when InnerTubeX had no stream")
            assertTrue(lines.any { it.startsWith("YouTube tier=innertubex-primary resolved=false") }, lines.toString())

            // A refused transport shape is not fatal either: the walk still gets its turn.
            val refused = StubStreamExtractor(failure = DesktopInnerTubeXException("InnerTubeX returned a SABR stream"))
            playerCalls.clear()
            val third = DesktopYouTubeResolver(
                http,
                preferences = { null },
                cipher = RecordingCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = refused),
            )
            assertIs<PreparedAudio.Progressive>(third.primary(request()))
            assertEquals(1, refused.extractions)
            assertTrue(playerCalls.isNotEmpty())
        } finally {
            http.close()
        }
    }

    /**
     * A refresh the queue asks for after a rejected URL must not leave the rejected URL behind. Here the refresh
     * resolves nothing at all — the library has no stream and every walk client hands back a format no cipher can open —
     * so the only thing that can retire the old entry is the refresh itself.
     */
    @Test
    fun aForcedRefreshRetiresTheRejectedEntryEvenWhenItResolvesNothing() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { call ->
            if (!call.url.encodedPath.endsWith("/player")) return@MockEngine respond("{}")
            respond(playerBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val cache = DesktopResolvedStreamCache()
        val library = StubStreamExtractor(answer = extractedStream())
        try {
            val resolver = DesktopYouTubeResolver(
                http,
                preferences = { null },
                cipher = UndecipherableCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = library),
                cache = cache,
            )
            assertIs<PreparedAudio.Progressive>(resolver.primary(request()))
            assertEquals(1, cache.size())

            // The rejection the queue is recovering from: the library is silent and the walk cannot open a format.
            library.answer = null
            assertNull(resolver.primary(request().copy(forceFreshStream = true)))
            assertEquals(0, cache.size())

            // Nothing stale comes back afterwards: the next ordinary resolve asks the library again.
            library.answer = extractedStream()
            val recovered = assertIs<PreparedAudio.Progressive>(resolver.primary(request()))
            assertEquals(ITX_URL, recovered.stream.uri)
            // Answered, refreshed with nothing, then answered again: every attempt reached the library, none the cache.
            assertEquals(3, library.extractions)
        } finally {
            http.close()
        }
    }

    /**
     * Every client the playback-client setting offers has to reach InnerTubeX as its own profile or be explicitly
     * unsupported. The library's catalog is the authority on both halves: WEB_REMIX is the stable automatic profile,
     * and a client it marks broken or keeps for its own probes is one the resolver does not pin.
     */
    @Test
    fun everyOfferedPlaybackClientIsEitherMappedToItsProfileOrExplicitlyUnsupported() {
        // The mapping is total and one-to-one, so no choice can fall out of it into an unknown profile id.
        val ids = PlaybackClientChoice.entries.map { it.innerTubeXCatalogId() }
        assertEquals(PlaybackClientChoice.entries.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
        assertEquals(DesktopYouTubeClients.MAIN_CLIENT.clientName, PlaybackClientChoice.WEB_REMIX.innerTubeXCatalogId())
        assertEquals("WEB_REMIX", innerTubeXOverrideId(PlaybackClientChoice.WEB_REMIX))

        for (choice in PlaybackClientChoice.entries) {
            val manifest = assertNotNull(
                PlaybackClientCatalog.find(choice.innerTubeXCatalogId())?.manifest,
                "${choice.name} names a profile InnerTubeX no longer lists",
            )
            val usable = manifest.lifecycle != ClientLifecycle.BROKEN &&
                manifest.selectionMode != ClientSelectionMode.PROBE_ONLY
            if (usable) {
                assertEquals(choice.innerTubeXCatalogId(), innerTubeXOverrideId(choice), choice.name)
            } else {
                assertNull(innerTubeXOverrideId(choice), "${choice.name} cannot be pinned: $manifest")
            }
        }
        assertNull(innerTubeXOverrideId(null))
    }

    @Test
    fun aManualWebRemixChoiceReachesInnerTubeXAsItsOwnProfile() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { respond("{}") })
        val library = StubStreamExtractor(answer = extractedStream())
        val preferences = PreferenceSnapshot.EMPTY.toEditor().apply {
            this[AutoChoosePlaybackClientKey] = false
            this[PlayerStreamClientKey] = "WEB_REMIX"
        }.snapshot()
        try {
            val resolver = DesktopYouTubeResolver(
                http,
                preferences = { preferences },
                cipher = RecordingCipher(),
                innerTubeX = DesktopInnerTubeXTier(http, extractorOverride = library),
            )
            assertIs<PreparedAudio.Progressive>(resolver.primary(request()))
            assertEquals("WEB_REMIX", library.lastHints?.playbackClientOverrideId)
        } finally {
            http.close()
        }
    }

    /** A stream the InnerTubeX tier would map, with no reported Instant so the URL's `expire` decides the window. */
    private fun extractedStream(): ExtractedStream = ExtractedStream(
        videoId = videoId,
        audioUrl = ITX_URL,
        headers = mapOf("User-Agent" to "itx-agent"),
        loudnessDb = null,
        expiresAt = null,
        contentLengthBytes = 1_000L,
        itag = 251,
        mimeType = "audio/webm; codecs=\"opus\"",
        codecs = "opus",
        bitrate = 128_000,
        sampleRate = 48_000,
        clientName = "WEB_REMIX",
        profileId = "web-remix",
        requireBoundedRange = false,
        rangeChunkSizeBytes = 1_048_576L,
    )

    /** The library extractor, stubbed: the order test must not reach a network the suite does not own. */
    private class StubStreamExtractor(
        /** Mutable so one resolver can be asked twice: answered, then silent, then answered again. */
        var answer: ExtractedStream? = null,
        private val failure: Throwable? = null,
    ) : StreamExtractor {
        var extractions = 0
        var lastHints: ContentHints? = null

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            extractions++
            lastHints = hints
            failure?.let { throw it }
            return answer
        }
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

    /** Opens nothing, so every ranked format is skipped and the walk answers null instead of failing. */
    private class UndecipherableCipher : DesktopYouTubeStreamCipher {
        override suspend fun signatureTimestamp(videoId: String): Int = 12345

        override suspend fun playableUrl(
            videoId: String,
            url: String?,
            signatureCipher: String?,
            legacyCipher: String?,
        ): String? = null
    }

    private companion object {
        val CIPHERED_FORMAT =
            "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":129000," +
                "\"audioSampleRate\":\"44100\",\"contentLength\":\"1000\"," +
                "\"signatureCipher\":\"s=abc&sp=sig&url=https%3A%2F%2Fcdn.invalid%2Fstream%3F" +
                "expire%3D1800000000%26n%3Dabc\"}"
        const val TRANSFORMED_URL = "https://cdn.invalid/stream?expire=1800000000&n=transformed&sig=deciphered"
        const val ITX_URL = "https://cdn.invalid/itx?expire=1800000000"
    }
}
