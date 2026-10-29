package moe.rukamori.archivetune.shared.data.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.settings.AllowAgeRestrictedKey
import moe.rukamori.archivetune.shared.domain.settings.AudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.manualPlaybackClient
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val REQUEST_TIMEOUT_MS = 30_000L
private const val MAX_SEARCHED_IDS = 64

private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

/** The audio itags the shipped resolver prefers, best first, the same ladder the Android walk uses. */
private val PREFERRED_ITAGS = listOf(251, 140, 250, 249, 141, 139)

private val LOGIN_REASONS = listOf("sign in", "sign-in", "login")
private val BOT_REASONS = listOf("bot", "unusual traffic", "automated")

/**
 * The iOS YouTube chain, mirroring the app's shipped `YTPlayerUtils -> InnerTubeX -> NewPipe -> Echo` tiers:
 *
 * - `primary` is the shipped client walk: the preferred client, then the last one that resolved, then the app's
 *   fallback clients (VISIONOS, ANDROID_VR_1_65_10, WEB_REMIX, WEB, MWEB, WEB_CREATOR), then the main WEB_REMIX
 *   client and, while age-restricted playback is opted in, the two embedded bypass clients. Each attempt reads the
 *   same `player` endpoint and takes the best audio format with a plain (unciphered) URL — ciphered formats belong
 *   to the InnerTubeX tier, whose cipher service exists for them.
 * - `fallback(INNERTUBE_X)` is the full InnerTubeX extractor: config parsing plus the pure-Kotlin cipher, asked as a
 *   visitor exactly like the Android tier, and kept off SABR/HLS/bounded-range responses because the engine takes a
 *   plain progressive resource.
 * - `fallback(NEWPIPE)` and `fallback(ECHO)` are JVM extractors upstream and honestly report unavailability here.
 */
class IosYouTubeResolver(
    private val http: HttpClient,
    private val transport: IosYouTubeBrowseTransport,
    private val preferences: () -> PreferenceSnapshot?,
    private val personalScope: suspend () -> String?,
) : YouTubeResolutionPort {

    // A derived client shares the graph's engine and adds what InnerTubeX expects: status exceptions and JSON bodies.
    // It is never closed on its own, because closing it would close the shared engine; the graph closes that.
    private val client by lazy { createHttpClient() }
    private val extractor by lazy { createExtractor() }

    @Volatile
    private var prewarmed = false

    // The last client that produced a stream is tried first on the next track, as on the shipped resolver.
    @Volatile
    private var lastSuccessful: YouTubeClient? = null

    // Searched ids are untrusted until the title gate accepts them; direct and media ids are trusted.
    // Insertion-ordered and bounded: the oldest entry leaves once the map outgrows its bound.
    private val searchedIds = LinkedHashMap<String, String>()

    private fun rememberSearchedId(mediaId: String, videoId: String) {
        searchedIds[mediaId] = videoId
        while (searchedIds.size > MAX_SEARCHED_IDS) {
            searchedIds.remove(searchedIds.entries.first().key)
        }
    }

    override suspend fun primary(request: AudioResolveRequest): PreparedAudio? {
        val target = targetVideoId(request) ?: return null
        val qualities = qualityAttempts(requestAudioQuality(request))
        var lastReason: String? = null
        var loginRequired = false
        var botBlocked = false
        for (attempt in qualities) {
            for (streamClient in clientOrder(preferences)) {
                val response =
                    try {
                        innerTubePlayer(streamClient, target.videoId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        continue
                    }
                val status = response.playabilityStatus
                if (status.status != "OK") {
                    lastReason = status.reason ?: status.status
                    loginRequired = loginRequired || status.status == "LOGIN_REQUIRED" ||
                        status.reason?.let { reason -> LOGIN_REASONS.any(reason.lowercase()::contains) } == true
                    botBlocked = botBlocked ||
                        status.reason?.let { reason -> BOT_REASONS.any(reason.lowercase()::contains) } == true
                    continue
                }
                val format = pickFormat(response.streamingData?.adaptiveFormats.orEmpty(), attempt) ?: continue
                lastSuccessful = streamClient
                return PreparedAudio.Progressive(
                    format.toDirectStream(streamClient, response, target, accountScope(target))
                )
            }
        }
        if (loginRequired && !botBlocked) throw TerminalPlaybackAuthentication()
        if (lastReason != null) return null
        return null
    }

    override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? {
        val target = targetVideoId(request) ?: return null
        return when (tier) {
            YouTubeFallbackTier.INNERTUBE_X -> resolveInnerTubeX(target)
            // NewPipe and Echo are JVM extractors upstream; no iOS build exists, so they stay honestly silent.
            YouTubeFallbackTier.NEWPIPE, YouTubeFallbackTier.ECHO -> null
        }
    }

    /** The video id a request resolves to, marking whether the provider id itself is already authoritative. */
    private suspend fun targetVideoId(request: AudioResolveRequest): IosYouTubeTarget? {
        request.directIds[AudioSourceType.YOUTUBE]?.takeIf { VIDEO_ID_REGEX.matches(it) }?.let { id ->
            return IosYouTubeTarget(id, trusted = true)
        }
        if (VIDEO_ID_REGEX.matches(request.mediaId)) return IosYouTubeTarget(request.mediaId, trusted = true)
        searchedIds[request.mediaId]?.let { return IosYouTubeTarget(it, trusted = false) }
        val query = searchQuery(request)
        val videoId = runCatching { transport.firstSongVideoId(query) }.getOrNull() ?: return null
        rememberSearchedId(request.mediaId, videoId)
        return IosYouTubeTarget(videoId, trusted = false)
    }

    private fun searchQuery(request: AudioResolveRequest): String {
        val artist = request.target.artists.firstOrNull().orEmpty()
        return listOf(request.target.title, artist).filter(String::isNotBlank).joinToString(" ")
            .ifBlank { request.mediaId }
    }

    /** The session cookie the walk runs under, and the account scope the stream must name. Anonymous stays null. */
    private suspend fun accountScope(target: IosYouTubeTarget): String? {
        transport.refreshSession(preferences())
        return if (transport.signedIn()) personalScope() else null
    }

    /** One player request under the session's current sign-in, returning null's opposite only. */
    private suspend fun innerTubePlayer(client: YouTubeClient, videoId: String): PlayerResponse =
        innerTube.player(client, videoId).body()

    private suspend fun resolveInnerTubeX(target: IosYouTubeTarget): PreparedAudio? {
        prewarmOnce()
        val stream =
            extractor.extract(
                videoId = target.videoId,
                hints =
                    ContentHints(wantVideo = false).withStreamCapabilities(
                        allowHls = false,
                        allowSabr = false,
                        allowBoundedRange = false,
                    ),
                audioQuality = requestAudioQualityInnerTubeX(),
            ) ?: return null
        if (stream.audioUrl.contains("/manifest/hls") || stream.audioUrl.contains(".m3u8")) {
            throw IosInnerTubeXException("InnerTubeX returned an HLS manifest for ${target.videoId}")
        }
        if (stream.sabrBootstrap != null) {
            throw IosInnerTubeXException("InnerTubeX returned a segmented SABR stream for ${target.videoId}")
        }
        if (stream.requireBoundedRange) {
            throw IosInnerTubeXException("InnerTubeX returned a byte-range-only stream for ${target.videoId}")
        }
        lastSuccessful = null
        return PreparedAudio.Progressive(stream.toDirectStream(target, accountScope(target)))
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

    private val innerTube: InnerTube by lazy { InnerTube(httpClient = client) }

    private fun createExtractor(): InnerTubeExtractor {
        val playerConfigStore =
            RemotePlayerConfigStore(httpClient = client, repository = PlayerConfigRepository.disabled())
        return InnerTubeExtractor(
            configParser = YtConfigParserImpl(client, innerTube, playerConfigStore),
            cipherService = YouTubeCipherService(client, playerConfigStore),
            innerTube = innerTube,
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

    /** The quality the request or the saved playback preference asks for; unknown values stay automatic. */
    private fun requestAudioQuality(request: AudioResolveRequest): IosYouTubeAudioQuality =
        IosYouTubeAudioQuality.fromRequestValue(
            request.qualities[AudioSourceType.YOUTUBE] ?: preferences()?.get(AudioQualityKey)
        )

    private fun requestAudioQualityInnerTubeX(): InnerTubeXAudioQuality =
        when (IosYouTubeAudioQuality.fromRequestValue(preferences()?.get(AudioQualityKey))) {
            IosYouTubeAudioQuality.AUTO -> InnerTubeXAudioQuality.AUTO
            IosYouTubeAudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            // InnerTubeX has no distinct highest tier; HIGH is its best lossy audio.
            IosYouTubeAudioQuality.HIGH, IosYouTubeAudioQuality.HIGHEST -> InnerTubeXAudioQuality.HIGH
        }

    /**
     * The shipped order: the chosen client, the last successful one, then the app's fallbacks, then the main client,
     * then the embedded age-gate bypasses only while the user opted in to age-restricted content.
     */
    private fun clientOrder(preferences: () -> PreferenceSnapshot?): List<YouTubeClient> {
        val snapshot = preferences()
        val preferred = preferredClient(snapshot?.manualPlaybackClient())
        return buildList {
            add(preferred)
            lastSuccessful?.takeIf { it != preferred }?.let { add(it) }
            addAll(STREAM_FALLBACK_CLIENTS)
            if (preferred != YouTubeClient.WEB_REMIX) add(YouTubeClient.WEB_REMIX)
            if (snapshot?.get(AllowAgeRestrictedKey) == true) addAll(AGE_GATE_BYPASS_CLIENTS)
        }.distinct()
    }

}

private class IosYouTubeTarget(val videoId: String, val trusted: Boolean)

private class IosInnerTubeXException(message: String) : IllegalStateException(message)

private enum class IosYouTubeAudioQuality {
    AUTO,
    LOW,
    HIGH,
    HIGHEST,
    ;

    companion object {
        fun fromRequestValue(value: String?): IosYouTubeAudioQuality =
            when (value?.trim()?.uppercase()) {
                "LOW" -> LOW
                "HIGH" -> HIGH
                "HIGHEST" -> HIGHEST
                else -> AUTO
            }
    }
}

/** The quality ladder for one request, the same attempts the shipped resolver makes. */
private fun qualityAttempts(quality: IosYouTubeAudioQuality): List<IosYouTubeAudioQuality> =
    when (quality) {
        IosYouTubeAudioQuality.HIGHEST ->
            listOf(IosYouTubeAudioQuality.HIGHEST, IosYouTubeAudioQuality.HIGH, IosYouTubeAudioQuality.LOW)
        IosYouTubeAudioQuality.HIGH -> listOf(IosYouTubeAudioQuality.HIGH, IosYouTubeAudioQuality.LOW)
        IosYouTubeAudioQuality.AUTO ->
            listOf(IosYouTubeAudioQuality.AUTO, IosYouTubeAudioQuality.HIGH, IosYouTubeAudioQuality.LOW)
        IosYouTubeAudioQuality.LOW ->
            listOf(IosYouTubeAudioQuality.LOW, IosYouTubeAudioQuality.HIGH, IosYouTubeAudioQuality.AUTO)
    }

/** The app's stream-client preference as the client it names; unknown values keep the WEB_REMIX default. */
private fun preferredClient(value: String?): YouTubeClient =
    when (value?.trim()?.uppercase()) {
        "ANDROID_VR" -> YouTubeClient.ANDROID_VR_1_65_10
        "IOS" -> YouTubeClient.IOS
        "TVHTML5" -> YouTubeClient.TVHTML5
        "ANDROID_MUSIC" -> YouTubeClient.ANDROID_MUSIC
        else -> YouTubeClient.WEB_REMIX
    }

private val STREAM_FALLBACK_CLIENTS =
    listOf(
        YouTubeClient.VISIONOS,
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB,
        YouTubeClient.MWEB,
        YouTubeClient.WEB_CREATOR,
    )

private val AGE_GATE_BYPASS_CLIENTS =
    listOf(
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.WEB_EMBEDDED_PLAYER,
    )

/** The best audio format for the attempt: audio-only, playable plain URL, no cipher, ranked like the shipped one. */
private fun pickFormat(
    formats: List<PlayerResponse.StreamingData.Format>,
    quality: IosYouTubeAudioQuality,
): PlayerResponse.StreamingData.Format? {
    val audio =
        formats.filter { format ->
            format.isAudio && format.url?.isNotBlank() == true &&
                format.signatureCipher == null && format.cipher == null
        }
    if (audio.isEmpty()) return null
    return when (quality) {
        IosYouTubeAudioQuality.LOW -> audio.minByOrNull { it.bitrate }
        else -> audio.maxByOrNull { itagRank(it) * 1_000_000 + it.bitrate }
    }
}

private fun itagRank(format: PlayerResponse.StreamingData.Format): Int {
    val index = PREFERRED_ITAGS.indexOf(format.itag)
    return if (index < 0) 0 else PREFERRED_ITAGS.size - index
}

private fun PlayerResponse.StreamingData.Format.toDirectStream(
    client: YouTubeClient,
    response: PlayerResponse,
    target: IosYouTubeTarget,
    accountScope: String?,
): DirectStream {
    val fullMime = mimeType
    return DirectStream(
        uri = url.orEmpty(),
        mimeType = mimeBase(fullMime),
        codecs = mimeCodecs(fullMime),
        contentLength = contentLength?.takeIf { it > 0L },
        label = "YouTube ${client.friendlyName ?: client.clientName} $itag ${bitrate / 1000} kbps",
        source = AudioSourceType.YOUTUBE,
        matchedTitle = response.videoDetails?.title,
        matchedArtist = response.videoDetails?.author?.removeSuffix(" - Topic"),
        matchedDurationMs = response.videoDetails?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0L }?.times(1_000L),
        trustedDirectId = target.trusted,
        sampleRate = audioSampleRate?.takeIf { it > 0 },
        // googlevideo checks the client identity that minted the URL, so the client's headers travel with it.
        headers = PlaybackHeaders.of(
            mapOf(
                "User-Agent" to client.userAgent,
                "Referer" to "https://music.youtube.com/",
            ),
        ),
        accountScope = accountScope,
    )
}

private fun ExtractedStream.toDirectStream(target: IosYouTubeTarget, accountScope: String?): DirectStream {
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
        trustedDirectId = target.trusted,
        sampleRate = sampleRate?.takeIf { it > 0 },
        headers = PlaybackHeaders.of(
            headers.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() && value.none { it == '\r' || it == '\n' } }
                .entries.distinctBy { it.key.lowercase() }
                .associate { it.key to it.value },
        ),
        accountScope = accountScope,
    )
}

/** The container type without its codecs parameter, for example `audio/webm`. */
private fun mimeBase(mimeType: String): String = mimeType.substringBefore(';').trim()

/** The full codec list inside the mime type's quotes, which is what the player's codec field carries. */
private fun mimeCodecs(mimeType: String): String =
    Regex("""codecs="([^"]*)"""").find(mimeType)?.groupValues?.getOrNull(1).orEmpty()
