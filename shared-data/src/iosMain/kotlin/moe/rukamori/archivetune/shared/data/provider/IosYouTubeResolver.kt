package moe.rukamori.archivetune.shared.data.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.data.potoken.IosPoTokenSupport
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens
import moe.rukamori.archivetune.shared.data.potoken.appendPoTokenToStreamUrl
import moe.rukamori.archivetune.shared.data.potoken.poTokenForWebClient
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

/** The BotGuard mint allowance inside a tier bound, leaving the rest to extraction. */
private const val MINT_BUDGET_MS = 3_000L

/** Echo's last-byte probe stays well inside its 20 s tier bound. */
private const val ECHO_PROBE_TIMEOUT_MS = 8_000L
private const val ECHO_PROBE_CHUNK_BYTES = 512L * 1024L
private const val ECHO_WEBM_BONUS = 10_240
private const val PRIVATE_TRACK_TYPE = "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

/** The audio itags the shipped resolver prefers, best first, the same ladder the Android walk uses. */
private val PREFERRED_ITAGS = listOf(251, 140, 250, 249, 141, 139)

private val LOGIN_REASONS = listOf("sign in", "sign-in", "login")
private val BOT_REASONS = listOf("bot", "unusual traffic", "automated")

/** The playability statuses Echo's main request reads as its age-gate signal. */
private val AGE_RESTRICTED_STATUSES =
    listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

private val ECHO_MAIN_CLIENT = YouTubeClient.WEB_REMIX

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
 * - `fallback(NEWPIPE)` runs the NewPipe tier's mechanism directly: the `org.schabi.newpipe.extractor` artifact is
 *   JVM-only, so the tier makes the same anonymous visitor player requests over the extractor's client set — its
 *   ANDROID_VR pass first, then the shipped tier's ANDROID_MUSIC/IOS/WEB_REMIX walk — and deciphers
 *   `signatureCipher`/`cipher` and the n parameter through the shared QuackJS cipher, the pure-Kotlin counterpart
 *   of NewPipe's JavaScript-player deobfuscation that the InnerTubeX tier already runs here.
 * - `fallback(ECHO)` runs the Echo-Music cascade over the live session: WEB_REMIX first for the age-gate and
 *   private-track signals, then VISIONOS/ANDROID_VR 1.65/TVHTML5/ANDROID_VR 1.43/IPADOS/IOS/WEB_CREATOR with
 *   login-required clients skipped while anonymous, the same deciphering, the HEAD+Range validation probe, and the
 *   last client accepted unvalidated. Android's WebView-hosted meriyah/astring solver and WebView token generator
 *   are the Echo pieces that stay JVM-only; the shared QuackJS cipher and the shipped BotGuard support stand in
 *   for them, so the tier stays honest about what it is.
 *
 * Every fallback tier writes one diagnostics line with its outcome, as `PlaybackDiagnostics` does on Android.
 */
class IosYouTubeResolver(
    private val http: HttpClient,
    private val transport: IosYouTubeBrowseTransport,
    private val preferences: () -> PreferenceSnapshot?,
    private val personalScope: suspend () -> String?,
    private val poTokens: IosPoTokenSupport? = null,
    private val report: (String) -> Unit = {},
) : YouTubeResolutionPort {

    // A derived client shares the graph's engine and adds what InnerTubeX expects: status exceptions and JSON
    // bodies. It is never closed on its own, because closing it would close the shared engine; the graph closes that.
    private val client by lazy { createHttpClient() }
    private val extractor by lazy { createExtractor() }

    // The validation probe answers with any status: a 403 is a result here, not an exception.
    private val probeClient by lazy { createProbeClient() }

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
        // One BotGuard mint per resolve under the current transport session, as the shipped chain
        // mints once for its main client; the pair then feeds serviceIntegrityDimensions and pot=.
        transport.refreshSession(preferences())
        val minted = poTokens?.mintForPlayback(target.videoId, transport.innerTubeSession.sessionSnapshot())
        for (attempt in qualities) {
            for (streamClient in clientOrder(preferences)) {
                val response =
                    try {
                        innerTubePlayer(
                            streamClient,
                            target.videoId,
                            poToken = poTokenForWebClient(streamClient.clientName, minted),
                        )
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
                    format.toDirectStream(
                        streamClient,
                        response,
                        target,
                        accountScope(target),
                        poTokenForWebClient(streamClient.clientName, minted),
                    )
                )
            }
        }
        if (loginRequired && !botBlocked) throw TerminalPlaybackAuthentication()
        if (lastReason != null) return null
        return null
    }

    override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? {
        val name = androidTierName(tier)
        val target = targetVideoId(request) ?: return null
        val started = TimeSource.Monotonic.markNow()
        // The tier bounds itself the way the desktop host does, so its own timeout comes back as a null
        // outcome; the caller's cancellation and the terminal-auth signal always propagate, and the
        // finally block writes the tier's one diagnostics line on every exit path.
        var resolved = false
        var reason: String? = null
        try {
            var timedOut = true
            val audio =
                withTimeoutOrNull(tier.timeoutMs) {
                    val result =
                        when (tier) {
                            YouTubeFallbackTier.INNERTUBE_X -> resolveInnerTubeX(target)
                            YouTubeFallbackTier.NEWPIPE -> resolveNewPipe(target, request)
                            YouTubeFallbackTier.ECHO -> resolveEcho(target)
                        }
                    timedOut = false
                    result
                }
            resolved = audio != null
            if (timedOut) reason = "timeout"
            return audio
        } catch (auth: TerminalPlaybackAuthentication) {
            reason = auth::class.simpleName
            throw auth
        } catch (cancelled: CancellationException) {
            reason = cancelled::class.simpleName
            throw cancelled
        } catch (failure: Throwable) {
            reason = failure::class.simpleName
            return null
        } finally {
            logTier(name, resolved, started.elapsedNow().inWholeMilliseconds, reason)
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
        val videoId =
            try {
                transport.firstSongVideoId(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
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
    private suspend fun innerTubePlayer(client: YouTubeClient, videoId: String, poToken: String?): PlayerResponse =
        innerTube.player(client, videoId, poToken = poToken).body()

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

    /**
     * The NewPipe tier's iOS mechanism. The extractor artifact itself is JVM-only, so the tier does what the
     * extractor does: anonymous visitor player requests whose ciphered formats are solved by the player script.
     * The client order mirrors it — NewPipe's own ANDROID_VR pass first, then the same visitor clients the shipped
     * tier walks. [YouTubeCipherService.processFormats] is the QuackJS counterpart of NewPipe's
     * YoutubeJavaScriptPlayerManager deobfuscation: the signature and the n parameter are solved by the same player
     * script, never pattern-matched, so a rotated player keeps working the way it does upstream.
     */
    private suspend fun resolveNewPipe(target: IosYouTubeTarget, request: AudioResolveRequest): PreparedAudio? {
        prewarmOnce()
        transport.refreshSession(preferences())
        // The anonymous visitor config is the tier's signature-timestamp, visitor and player source: NewPipe
        // resolves as a visitor because a track the signed-in pipeline was refused is often served to one.
        val config = ytConfigParser.fetchConfig(target.videoId, useLoginCookies = false)
        val qualities = qualityAttempts(requestAudioQuality(request))
        // The mint binds the visitor session, not the signed-in one the player requests deliberately leave out.
        val visitorSession =
            transport.innerTubeSession.sessionSnapshot().let { session ->
                if (session.sapisid.isNullOrBlank()) {
                    session
                } else {
                    session.copy(cookie = null, sapisid = null, dataSyncId = null)
                }
            }
        val minted = boundedMint(target.videoId, visitorSession)
        for (streamClient in NEWPIPE_TIER_CLIENTS) {
            if (streamClient.loginRequired) continue
            val response =
                try {
                    innerTube.player(
                        streamClient,
                        target.videoId,
                        signatureTimestamp = config.signatureTimestamp.takeIf { streamClient.useSignatureTimestamp },
                        poToken = poTokenForWebClient(streamClient.clientName, minted),
                        requestVisitorData = config.visitorData,
                    ).body<PlayerResponse>()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
            if (response.playabilityStatus.status != "OK") continue
            val formats =
                try {
                    cipherService.processFormats(
                        config.playerUrl,
                        response.streamingData?.adaptiveFormats.orEmpty(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
            for (attempt in qualities) {
                pickFormat(formats, attempt)?.let { format ->
                    lastSuccessful = null
                    return PreparedAudio.Progressive(
                        format.toDirectStream(
                            client = streamClient,
                            response = response,
                            target = target,
                            accountScope = null,
                            poToken = poTokenForWebClient(streamClient.clientName, minted),
                            tierLabel = "NewPipe",
                        ),
                    )
                }
            }
        }
        return null
    }

    /**
     * The Echo tier's iOS mechanism, the last resort: the signed-in session cascade — WEB_REMIX first for the
     * age-gate and private-track signals, then the measured fallback clients — with the same deciphering and the
     * HEAD+Range validation probe the Android echo resolver runs, accepting the last client unvalidated. The
     * WebView-hosted meriyah/astring solver and the WebView token generator are the Android pieces that do not
     * exist here; the shared QuackJS cipher and the BotGuard mint the shipped path already carries stand in.
     */
    private suspend fun resolveEcho(target: IosYouTubeTarget): PreparedAudio? {
        prewarmOnce()
        transport.refreshSession(preferences())
        val liveSession = transport.innerTubeSession
        val signedIn = transport.signedIn()
        val config = ytConfigParser.fetchConfig(target.videoId, useLoginCookies = false)
        val minted = boundedMint(target.videoId, liveSession.sessionSnapshot())

        // The main client request always runs: its playability names the age gate and the track type the cascade
        // indexes on, and its failure ends the tier the way Echo's does.
        var main =
            liveSession.player(
                ECHO_MAIN_CLIENT,
                target.videoId,
                signatureTimestamp = config.signatureTimestamp.takeIf { ECHO_MAIN_CLIENT.useSignatureTimestamp },
                poToken = poTokenForWebClient(ECHO_MAIN_CLIENT.clientName, minted),
            ).body<PlayerResponse>()
        val wasAgeRestricted = main.playabilityStatus.status in AGE_RESTRICTED_STATUSES
        if (wasAgeRestricted && signedIn) {
            // A logged-in age gate hands the main answer to the creator client, as Echo's does.
            sessionPlayerOrNull(YouTubeClient.WEB_CREATOR, target.videoId, signatureTimestamp = null, poToken = null)
                ?.takeIf { it.playabilityStatus.status == "OK" }
                ?.let { main = it }
        }
        val isPrivateTrack = main.videoDetails?.musicVideoType == PRIVATE_TRACK_TYPE
        val startIndex = if (isPrivateTrack) ECHO_PRIVATE_TRACK_START_INDEX else 0

        for (index in startIndex until ECHO_TIER_CLIENTS.size) {
            val streamClient = ECHO_TIER_CLIENTS[index]
            if (streamClient.loginRequired && !signedIn) continue
            val response =
                sessionPlayerOrNull(
                    streamClient,
                    target.videoId,
                    signatureTimestamp =
                        (if (wasAgeRestricted) null else config.signatureTimestamp)
                            .takeIf { streamClient.useSignatureTimestamp },
                    poToken =
                        poTokenForWebClient(streamClient.clientName, minted)
                            .takeIf { streamClient.useWebPoTokens },
                ) ?: continue
            if (response.playabilityStatus.status != "OK") continue
            val formats =
                try {
                    cipherService.processFormats(
                        config.playerUrl,
                        response.streamingData?.adaptiveFormats.orEmpty(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
            val format = echoPickAudio(formats) ?: continue
            if (response.streamingData?.expiresInSeconds == null) continue
            val privatelyOwned = response.videoDetails?.musicVideoType == PRIVATE_TRACK_TYPE
            var url = format.url ?: continue
            // The n parameter was already transformed with the signature. The client sets Echo names for its
            // append are exactly the web-po clients in this catalog, so the gate reduces to them plus a
            // privately owned track, which carries the video-bound token whatever the client.
            if (streamClient.useWebPoTokens || privatelyOwned) {
                val pot =
                    if (privatelyOwned && !streamClient.useWebPoTokens) {
                        minted?.playerToken
                    } else {
                        poTokenForWebClient(streamClient.clientName, minted)
                    }
                url = appendPoTokenToStreamUrl(url, pot)
            }
            val accepted =
                index == ECHO_TIER_CLIENTS.lastIndex || privatelyOwned ||
                    probeStreamUrl(url, format.contentLength)
            if (!accepted) continue
            lastSuccessful = null
            return PreparedAudio.Progressive(
                format.copy(url = url).toDirectStream(
                    client = streamClient,
                    response = response,
                    target = target,
                    accountScope = accountScope(target),
                    poToken = null,
                    tierLabel = "Echo",
                ),
            )
        }
        return null
    }

    /** One player request under the live transport session, null on failure like Echo's trapped calls. */
    private suspend fun sessionPlayerOrNull(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
    ): PlayerResponse? =
        try {
            transport.innerTubeSession.player(
                client,
                videoId,
                signatureTimestamp = signatureTimestamp,
                poToken = poToken,
            ).body<PlayerResponse>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /**
     * Echo's HEAD+Range probe: the last byte when the length is known, the first chunk otherwise. A
     * network-class failure proves nothing about the URL and accepts it, as Echo's IOException branch does;
     * an answered rejection rejects the candidate. The probe carries no auth: a media URL must never see
     * cookies or Authorization headers, including for signed-in clients.
     */
    private suspend fun probeStreamUrl(url: String, contentLength: Long?): Boolean =
        try {
            val range =
                if (contentLength != null && contentLength > 0L) {
                    "bytes=${contentLength - 1}-${contentLength - 1}"
                } else {
                    "bytes=0-${ECHO_PROBE_CHUNK_BYTES - 1}"
                }
            val response =
                probeClient.head(url) {
                    header(HttpHeaders.Range, range)
                }
            response.status.value in 200..299 || response.status == HttpStatusCode.MethodNotAllowed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure is IOException
        }

    /** A mint with the same allowance the desktop tiers grant inside their bound. */
    private suspend fun boundedMint(videoId: String, session: InnerTube.SessionSnapshot): MintedPoTokens? =
        try {
            withTimeoutOrNull(MINT_BUDGET_MS) { poTokens?.mintForPlayback(videoId, session) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Echo's format pick: the highest-bitrate audio, with the webm container preference it adds. */
    private fun echoPickAudio(
        formats: List<PlayerResponse.StreamingData.Format>,
    ): PlayerResponse.StreamingData.Format? =
        formats
            .filter { it.isAudio && !it.url.isNullOrBlank() }
            .maxByOrNull { it.bitrate + if (it.mimeType.startsWith("audio/webm")) ECHO_WEBM_BONUS else 0 }

    /** The `PlaybackDiagnostics` line Android writes once per tier, with the same tier names. */
    private fun logTier(name: String, resolved: Boolean, elapsedMs: Long, reason: String?) {
        report(
            "YouTube tier=$name resolved=$resolved elapsedMs=$elapsedMs" +
                (reason?.let { " reason=$it" } ?: ""),
        )
    }

    /** The tier names Android's `shippedFallbackTiers` list writes, so the diagnostics read identically. */
    private fun androidTierName(tier: YouTubeFallbackTier): String =
        when (tier) {
            YouTubeFallbackTier.INNERTUBE_X -> "innertubex"
            YouTubeFallbackTier.NEWPIPE -> "newpipe"
            YouTubeFallbackTier.ECHO -> "echo"
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

    private val playerConfigStore by lazy {
        RemotePlayerConfigStore(httpClient = client, repository = PlayerConfigRepository.disabled())
    }

    // One cipher/parser pair serves the InnerTubeX extractor and the direct NewPipe/Echo tiers, so the bounded
    // player-script and program caches are not duplicated.
    private val cipherService by lazy { YouTubeCipherService(client, playerConfigStore) }
    private val ytConfigParser by lazy { YtConfigParserImpl(client, innerTube, playerConfigStore) }

    private fun createExtractor(): InnerTubeExtractor =
        InnerTubeExtractor(
            configParser = ytConfigParser,
            cipherService = cipherService,
            innerTube = innerTube,
            tokenProvider = poTokens?.tokenProvider,
        )

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

    private fun createProbeClient(): HttpClient =
        http.config {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = ECHO_PROBE_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = ECHO_PROBE_TIMEOUT_MS
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

/**
 * NewPipe's visitor client set, most likely first: its own extractor pass asks an ANDROID_VR profile, then the
 * shipped tier walks the same anonymous clients. NewPipe's deobfuscation is what makes a cipher-only response
 * usable here, so the walk avoids the clients that demand a login.
 */
private val NEWPIPE_TIER_CLIENTS =
    listOf(
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.IOS,
        YouTubeClient.WEB_REMIX,
    )

/** Echo's measured fallback cascade, in order: the last client is the one accepted without a validation probe. */
private val ECHO_TIER_CLIENTS =
    listOf(
        YouTubeClient.VISIONOS,
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.TVHTML5,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.IPADOS,
        YouTubeClient.IOS,
        YouTubeClient.WEB_CREATOR,
    )

/** A privately owned track starts the cascade at TVHTML5, as Echo's `PRIVATE_TRACK_STREAM_START_INDEX` does. */
private val ECHO_PRIVATE_TRACK_START_INDEX = ECHO_TIER_CLIENTS.indexOf(YouTubeClient.TVHTML5).coerceAtLeast(0)

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
    poToken: String?,
    tierLabel: String? = null,
): DirectStream {
    val fullMime = mimeType
    return DirectStream(
        uri = appendPoTokenToStreamUrl(url.orEmpty(), poToken),
        mimeType = mimeBase(fullMime),
        codecs = mimeCodecs(fullMime),
        contentLength = contentLength?.takeIf { it > 0L },
        label = "YouTube ${tierLabel?.plus(" ") ?: ""}${client.friendlyName ?: client.clientName} $itag ${bitrate / 1000} kbps",
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
