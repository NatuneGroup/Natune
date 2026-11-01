package moe.rukamori.archivetune.desktop.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val REQUEST_TIMEOUT_MS = 30_000L

/**
 * The InnerTubeX tier of the desktop YouTube chain, the desktop counterpart of the Android
 * `InnerTuneXStreamRepository`. InnerTubeX (`com.github.MetrolistGroup.innertubex`, GPL-3.0) brings its own InnerTube
 * client, player-config parser and pure-Kotlin cipher, so this tier shares nothing with the primary client walk and
 * still resolves when a YouTube change breaks it. It always asks as a visitor, as the Android tier does.
 *
 * The engine takes plain media URLs, so the capability hints keep the library off SABR, byte-range-only and HLS
 * clients, and the guards after `extract` refuse such a stream if a library regression hands one through anyway.
 */
internal class DesktopInnerTubeXTier(
    private val http: HttpClient,
    private val extractorOverride: StreamExtractor? = null,
    private val tokenProvider: TokenProvider? = null,
) {
    // A derived client shares the graph's engine and adds what InnerTubeX expects: status exceptions and JSON bodies.
    // It is never closed on its own, because closing it would close the shared engine; the graph closes that.
    private val client by lazy { createHttpClient() }
    private val extractor: StreamExtractor by lazy { extractorOverride ?: createExtractor() }

    @Volatile
    private var prewarmed = false

    suspend fun resolve(videoId: String, quality: DesktopYouTubeAudioQuality, trusted: Boolean): PreparedAudio? {
        prewarmOnce()
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints(wantVideo = false).withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = false,
            ),
            audioQuality = quality.toInnerTubeXQuality(),
        ) ?: return null
        if (stream.audioUrl.contains("/manifest/hls") || stream.audioUrl.contains(".m3u8")) {
            throw DesktopInnerTubeXException("InnerTubeX returned an HLS manifest for $videoId")
        }
        if (stream.sabrBootstrap != null) {
            throw DesktopInnerTubeXException("InnerTubeX returned a segmented SABR stream for $videoId")
        }
        if (stream.requireBoundedRange) {
            throw DesktopInnerTubeXException("InnerTubeX returned a byte-range-only stream for $videoId")
        }
        return PreparedAudio.Progressive(stream.toDirectStream(trusted))
    }

    /** Warming is an optimisation, so a failed warm never fails the tier and is retried by the next resolve. */
    private suspend fun prewarmOnce() {
        if (prewarmed) return
        try {
            extractor.prewarm()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        prewarmed = true
    }

    private fun createExtractor(): InnerTubeExtractor {
        val innerTube = InnerTube(httpClient = client)
        val playerConfigStore = RemotePlayerConfigStore(
            httpClient = client,
            repository = PlayerConfigRepository.disabled(),
        )
        return InnerTubeExtractor(
            configParser = YtConfigParserImpl(client, innerTube, playerConfigStore),
            cipherService = YouTubeCipherService(client, playerConfigStore),
            innerTube = innerTube,
            tokenProvider = tokenProvider,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClient(): HttpClient =
        http.config {
            expectSuccess = true
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
}

internal class DesktopInnerTubeXException(message: String) : IllegalStateException(message)

private fun DesktopYouTubeAudioQuality.toInnerTubeXQuality(): InnerTubeXAudioQuality =
    when (this) {
        DesktopYouTubeAudioQuality.AUTO -> InnerTubeXAudioQuality.AUTO
        DesktopYouTubeAudioQuality.LOW -> InnerTubeXAudioQuality.LOW
        // InnerTubeX has no distinct highest tier; HIGH is its best lossy audio.
        DesktopYouTubeAudioQuality.HIGH, DesktopYouTubeAudioQuality.HIGHEST -> InnerTubeXAudioQuality.HIGH
    }

private fun ExtractedStream.toDirectStream(trusted: Boolean): DirectStream {
    val fullMime = mimeType.orEmpty()
    val metadata = mediaMetadata
    return DirectStream(
        uri = audioUrl,
        mimeType = mimeBase(fullMime),
        codecs = codecs?.takeIf(String::isNotBlank) ?: mimeCodecs(fullMime),
        contentLength = contentLengthBytes?.takeIf { it > 0L },
        label = "YouTube InnerTubeX $clientName ${(bitrate ?: 0) / 1000} kbps",
        source = AudioSourceType.YOUTUBE,
        matchedTitle = metadata?.title,
        matchedArtist = metadata?.author?.removeSuffix(" - Topic"),
        matchedDurationMs = metadata?.durationSeconds?.takeIf { it > 0L }?.times(1_000L),
        trustedDirectId = trusted,
        sampleRate = sampleRate?.takeIf { it > 0 },
        // googlevideo checks the client identity that minted the URL, so the library's headers travel with it.
        headers = PlaybackHeaders.of(
            headers.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() && value.none { it == '\r' || it == '\n' } }
                .entries.distinctBy { it.key.lowercase() }
                .associate { it.key to it.value },
        ),
    )
}
