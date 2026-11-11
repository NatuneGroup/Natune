package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class OrderedAudioResolverTest {
    private val request = AudioResolveRequest("media", TrackMatching.Target("Song", listOf("Artist"), null, 180_000))
    private fun audio(source: AudioSourceType, title: String = "Song") = PreparedAudio.Progressive(
        DirectStream("https://audio.invalid/full", "audio/flac", "flac", null, source.name, source,
            title, "Artist", null, 180_000))
    private fun provider(type: AudioSourceType, block: suspend () -> PreparedAudio?) = object : AudioProviderRepository {
        override val source = type
        override suspend fun resolve(request: AudioResolveRequest) = block()
    }
    private fun youtube(primary: suspend () -> PreparedAudio? = { error("unexpected primary") },
        fallback: suspend (YouTubeFallbackTier) -> PreparedAudio? = { error("unexpected tier") }) = object : YouTubeResolutionPort {
        override suspend fun primary(request: AudioResolveRequest) = primary()
        override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest) = fallback(tier)
    }
    private fun settings(vararg enabled: AudioSourceType) = PlaybackSourceSnapshot(AudioSourceConfig.DEFAULT_ORDER, enabled.toSet())

    @Test fun shippedOrderMetadataGateAndRejectionsCannotBeBypassed() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val sources = listOf(AudioSourceType.DEEZER, AudioSourceType.QOBUZ, AudioSourceType.TIDAL).map { type ->
            provider(type) { seen += type; audio(type, if (type == AudioSourceType.TIDAL) "Different recording" else "Song") }
        }
        val result = OrderedAudioResolver(sources, youtube()).resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.DEEZER))
        assertEquals(listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ), seen)
        assertEquals(AudioSourceType.QOBUZ, assertIs<AudioResolution.Resolved>(result).audio.source)
        seen.clear()
        OrderedAudioResolver(sources, youtube()).resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ), rejected = setOf(AudioSourceType.TIDAL))
        assertEquals(listOf(AudioSourceType.QOBUZ), seen)
    }
    @Test fun rejectedExternalPinFallsThroughTheSamePermittedOrder() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val sources = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ).map { type ->
            provider(type) { seen += type; audio(type) }
        }
        val result = OrderedAudioResolver(sources, youtube()).resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
            pinned = AudioSourceType.TIDAL, rejected = setOf(AudioSourceType.TIDAL))
        assertEquals(listOf(AudioSourceType.QOBUZ), seen)
        assertEquals(AudioSourceType.QOBUZ, assertIs<AudioResolution.Resolved>(result).audio.source)
    }
    @Test fun youtubeTiersStayInM1OrderAndEchoRemainsLast() = runTest {
        val seen = mutableListOf<String>()
        val resolver = OrderedAudioResolver(emptyList(), youtube(
            { seen += "primary"; null },
            { tier -> seen += tier.name; if (tier == YouTubeFallbackTier.ECHO) audio(AudioSourceType.YOUTUBE) else null }))
        assertIs<AudioResolution.Resolved>(resolver.resolve(request, settings(AudioSourceType.YOUTUBE)))
        assertEquals(listOf("primary", "INNERTUBE_X", "NEWPIPE", "ECHO"), seen)
    }
    @Test fun primaryAndFallbackAuthFailuresAreTerminal() = runTest {
        val seen = mutableListOf<String>()
        val first = OrderedAudioResolver(emptyList(), youtube({ throw TerminalPlaybackAuthentication() }, {
            seen += it.name; null
        }))
        assertFailsWith<TerminalPlaybackAuthentication> { first.resolve(request, settings(AudioSourceType.YOUTUBE)) }
        assertTrue(seen.isEmpty())
        val second = OrderedAudioResolver(emptyList(), youtube({ null }, {
            seen += it.name; throw TerminalPlaybackAuthentication()
        }))
        assertFailsWith<TerminalPlaybackAuthentication> { second.resolve(request, settings(AudioSourceType.YOUTUBE)) }
        assertEquals(listOf("INNERTUBE_X"), seen)
    }
    @Test fun downloadNeverUsesSpotifyAndDisabledYoutubeNeverRuns() = runTest {
        val resolver = OrderedAudioResolver(listOf(provider(AudioSourceType.SPOTIFY) { error("not a download source") }), youtube())
        assertIs<AudioResolution.Unavailable>(resolver.resolve(request.copy(purpose = AudioPurpose.DOWNLOAD),
            settings(AudioSourceType.SPOTIFY)))
        assertIs<AudioResolution.Unavailable>(resolver.resolve(request, settings()))
    }
    @Test fun nativeRoutesStayExactAndAreNotOrdinaryEngineLocators() = runTest {
        val resolver = OrderedAudioResolver(emptyList(), youtube())
        for (uri in listOf("telegram://123/456", "tidal-dash://manifest/abc", "deezer://stream?u=opaque")) {
            assertEquals(AudioResolution.NativeRoute(uri), resolver.resolve(request, settings(), nativeUri = uri))
            assertFailsWith<IllegalArgumentException> {
                resolver.resolve(request.copy(purpose = AudioPurpose.DOWNLOAD), settings(), nativeUri = uri)
            }
        }
    }
    @Test fun providerTimeoutFallsThroughButExternalCancellationEscapes() = runTest {
        val resolver = OrderedAudioResolver(listOf(
            provider(AudioSourceType.TIDAL) { delay(31_000); audio(AudioSourceType.TIDAL) },
            provider(AudioSourceType.QOBUZ) { audio(AudioSourceType.QOBUZ) }), youtube())
        assertEquals(AudioSourceType.QOBUZ, assertIs<AudioResolution.Resolved>(resolver.resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ))).audio.source)
        val cancelled = OrderedAudioResolver(listOf(provider(AudioSourceType.TIDAL) {
            throw CancellationException("fixture")
        }), youtube())
        assertFailsWith<CancellationException> { cancelled.resolve(request, settings(AudioSourceType.TIDAL)) }
    }
    @Test fun actualHttpOwnedTimeoutDoesNotCancelTheNextSource() = runTest {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine) { engine {
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            addHandler { delay(13_000); respond("{}") }
        } }
        try {
            val tidal = KtorTidalRepository(client, TidalAccess("personal", "fixture-token"))
            val resolver = OrderedAudioResolver(listOf(tidal,
                provider(AudioSourceType.QOBUZ) { audio(AudioSourceType.QOBUZ) }), youtube())
            val result = assertIs<AudioResolution.Resolved>(resolver.resolve(request.copy(
                directIds = mapOf(AudioSourceType.TIDAL to "7")), settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ)))
            assertEquals(AudioSourceType.QOBUZ, result.audio.source)
            assertEquals(AudioAttemptResult.TIMEOUT, result.attempts.first().result)
        } finally { client.close() }
    }
    @Test fun providerAnswerForAnotherSourceFailsThatAttemptAndContinuesTheChain() = runTest {
        val resolver = OrderedAudioResolver(listOf(
            provider(AudioSourceType.TIDAL) { audio(AudioSourceType.QOBUZ) },
            provider(AudioSourceType.QOBUZ) { audio(AudioSourceType.QOBUZ) }), youtube())
        val result = assertIs<AudioResolution.Resolved>(resolver.resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.QOBUZ)))
        assertEquals(AudioSourceType.QOBUZ, result.audio.source)
        assertEquals(listOf(
            AudioAttempt(AudioSourceType.TIDAL, AudioAttemptResult.FAILED),
            AudioAttempt(AudioSourceType.QOBUZ, AudioAttemptResult.RESOLVED),
        ), result.attempts)
    }
    @Test fun providerAnswerForAnotherSourceStillFallsBackToYouTube() = runTest {
        val resolver = OrderedAudioResolver(listOf(
            provider(AudioSourceType.TIDAL) { audio(AudioSourceType.QOBUZ) }),
            youtube(primary = { audio(AudioSourceType.YOUTUBE) }))
        val result = assertIs<AudioResolution.Resolved>(resolver.resolve(request,
            settings(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)))
        assertEquals(AudioSourceType.YOUTUBE, result.audio.source)
        assertEquals(listOf(
            AudioAttempt(AudioSourceType.TIDAL, AudioAttemptResult.FAILED),
            AudioAttempt(AudioSourceType.YOUTUBE, AudioAttemptResult.RESOLVED),
        ), result.attempts)
    }

    @Test fun legacyModulesAttemptKeepsItsSlotBetweenNativeProvidersAndTerminal() = runTest {
        val seen = mutableListOf<String>()
        val sources = listOf(AudioSourceType.SPOTIFY, AudioSourceType.MODULES, AudioSourceType.TIDAL).map { type ->
            provider(type) { seen += type.name; null }
        }
        val resolver = OrderedAudioResolver(sources, youtube(primary = { seen += "YOUTUBE"; audio(AudioSourceType.YOUTUBE) }))
        val enabled = settings(AudioSourceType.TIDAL, AudioSourceType.MODULES, AudioSourceType.SPOTIFY, AudioSourceType.YOUTUBE)
        assertIs<AudioResolution.Resolved>(resolver.resolve(request, enabled))
        assertEquals(listOf("TIDAL", "MODULES", "SPOTIFY", "YOUTUBE"), seen)
        seen.clear()
        assertIs<AudioResolution.Resolved>(resolver.resolve(request, enabled.copy(
            order = AudioSourceConfig.parseOrder("SPOTIFY,MODULES,TIDAL,YOUTUBE"))))
        assertEquals(listOf("SPOTIFY", "MODULES", "TIDAL", "YOUTUBE"), seen)
        seen.clear()
        assertIs<AudioResolution.Unavailable>(resolver.resolve(request, enabled.copy(
            order = listOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE, AudioSourceType.MODULES),
            enabled = enabled.enabled - AudioSourceType.YOUTUBE)))
        assertEquals(listOf("TIDAL"), seen)
    }
}
