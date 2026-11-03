package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.desktop.potoken.DesktopPoTokenOwner
import moe.rukamori.archivetune.shared.data.potoken.appendPoTokenToStreamUrl
import moe.rukamori.archivetune.shared.data.provider.YouTubeBrowse
import moe.rukamori.archivetune.shared.data.provider.YouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.YouTubeResolutionPort
import moe.rukamori.archivetune.shared.data.provider.fetchYouTubeVisitorData
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.settings.AllowAgeRestrictedKey
import moe.rukamori.archivetune.shared.domain.settings.AudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.manualPlaybackClient
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private const val SEARCHED_ID_CACHE_SIZE = 64

/**
 * The desktop YouTube terminal tier over [DesktopYouTubeClients]. It walks the shipped quality ladder and client order
 * and returns the first ranked audio format that carries a direct URL. With a personal sign-in from [signIn], the
 * cookie-capable clients ask as that account and the login-only clients join the walk, as the shipped resolver lets
 * them for a signed-in user. Without one every request is a visitor request.
 *
 * The primary walk skips ciphered formats (`signatureCipher`/`cipher`): the base.js deobfuscation of the Android core is
 * not part of it, and a URL without its signature would only fail later in the engine.
 *
 * [fallback] runs the shipped extractor tiers in the order the shared failsafe walk gives them: InnerTubeX through
 * [DesktopInnerTubeXTier], then NewPipe through [DesktopNewPipeTier], which also deciphers what the primary walk
 * skipped. Echo runs on Android and iOS only; the desktop has no
 * Echo port, so its tier answers null, which the walk reads as a failed tier. Every tier
 * writes one diagnostic line with its outcome.
 */
internal class DesktopYouTubeResolver(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val search: YouTubeBrowseTransport? = null,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val signIn: () -> DesktopYouTubeSignIn? = { null },
    private val poTokens: DesktopPoTokenOwner? = null,
    private val innerTubeX: DesktopInnerTubeXTier = DesktopInnerTubeXTier(http, tokenProvider = poTokens?.tokenProvider),
    private val newPipe: DesktopNewPipeTier = DesktopNewPipeTier(
        http,
        locale,
        mint = { videoId, visitorData -> poTokens?.mintForPlayback(videoId, signIn(), visitorData) },
    ),
    private val log: (String) -> Unit = { DesktopDiagnosticLog.playbackTier(it) },
) : YouTubeResolutionPort {
    private val visitorLock = Mutex()
    private var visitorData: String? = null
    private var visitorFetched = false

    @Volatile
    private var lastSuccessful: DesktopYouTubeClient? = null

    // The fallback tiers resolve the same request after the primary did, so a searched id is kept instead of asking
    // the search transport again inside each bounded tier.
    private val searchedIds = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > SEARCHED_ID_CACHE_SIZE
    }

    /** Drops the searched video id of [mediaId] so the next resolution searches again, as the source refresh does. */
    fun invalidateSearch(mediaId: String) {
        synchronized(searchedIds) { searchedIds.remove(mediaId) }
    }

    private class Target(val videoId: String, val trusted: Boolean)

    override suspend fun primary(request: AudioResolveRequest): PreparedAudio? {
        val started = System.nanoTime()
        val target = targetFor(request) ?: return null
        return try {
            resolveVideo(target.videoId, target.trusted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Android logs the shipped tier only when it failed; a resolved primary writes nothing.
            logTier("shipped", false, started, failure.javaClass.simpleName)
            throw failure
        }
    }

    override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? {
        if (tier == YouTubeFallbackTier.ECHO) return null
        val name = androidTierName(tier)
        val target = targetFor(request) ?: return null
        val quality = DesktopYouTubeAudioQuality.fromRequestValue(preferences()?.get(AudioQualityKey))
        val started = System.nanoTime()
        // The tier bounds itself the way ResolveAudioStreamUseCase does on Android, so its own timeout comes back
        // as a null outcome and still earns its one diagnostic line; only the caller's cancellation stops the walk.
        val audio = try {
            withTimeoutOrNull(tier.timeoutMs) {
                when (tier) {
                    YouTubeFallbackTier.INNERTUBE_X -> innerTubeX.resolve(target.videoId, quality, target.trusted)
                    YouTubeFallbackTier.NEWPIPE -> newPipe.resolve(target.videoId, quality, target.trusted, visitor())
                    YouTubeFallbackTier.ECHO -> null
                }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            logTier(name, false, started, cancelled.javaClass.simpleName)
            return null
        } catch (auth: TerminalPlaybackAuthentication) {
            logTier(name, false, started, auth.javaClass.simpleName)
            throw auth
        } catch (failure: Throwable) {
            logTier(name, false, started, failure.javaClass.simpleName)
            return null
        }
        logTier(name, audio != null, started, if (audio == null) "timeout" else null)
        return audio
    }

    /** The `PlaybackDiagnostics` line Android writes once per tier: `YouTube tier=%s resolved=%s elapsedMs=%d reason=%s`. */
    private fun logTier(name: String, resolved: Boolean, startedNanos: Long, reason: String?) {
        log(
            "YouTube tier=$name resolved=$resolved elapsedMs=${elapsedMs(startedNanos)}" +
                (reason?.let { " reason=$it" } ?: ""),
        )
    }

    /** The tier names Android's `shippedFallbackTiers` list writes, so the diagnostic lines read identically. */
    private fun androidTierName(tier: YouTubeFallbackTier): String =
        when (tier) {
            YouTubeFallbackTier.INNERTUBE_X -> "innertubex"
            YouTubeFallbackTier.NEWPIPE -> "newpipe"
            YouTubeFallbackTier.ECHO -> "echo"
        }

    /**
     * The video artwork stream of [videoId] within [maxHeight] lines, through the NewPipe tier with this resolver's
     * visitor identity. Null when no client answered with a video type.
     */
    suspend fun resolveVideoArtwork(videoId: String, maxHeight: Int): DesktopVideoArtworkResult? =
        newPipe.resolveVideo(videoId, maxHeight, visitor())

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    private suspend fun targetFor(request: AudioResolveRequest): Target? {
        val direct = request.directIds[AudioSourceType.YOUTUBE] ?: request.mediaId.takeIf { VIDEO_ID.matches(it) }
        if (direct != null) return Target(direct, trusted = true)
        synchronized(searchedIds) { searchedIds[request.mediaId] }?.let { return Target(it, trusted = false) }
        val found = searchVideoId(request) ?: return null
        synchronized(searchedIds) { searchedIds[request.mediaId] = found }
        return Target(found, trusted = false)
    }

    private suspend fun searchVideoId(request: AudioResolveRequest): String? {
        val transport = search ?: return null
        val query = (listOf(request.target.title) + request.target.artists).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val page = transport.page(YouTubeBrowse.Search(query, ContentSearchFilter.SONGS), null)
        return page.entries.firstOrNull { it.kind == ContentKind.SONG && VIDEO_ID.matches(it.id) }?.id
    }

    private suspend fun visitor(): String? = visitorLock.withLock {
        if (!visitorFetched) {
            visitorData = try {
                fetchYouTubeVisitorData(http)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            visitorFetched = true
        }
        visitorData
    }

    private suspend fun resolveVideo(videoId: String, trusted: Boolean): PreparedAudio? {
        val snapshot = preferences()
        val quality = DesktopYouTubeAudioQuality.fromRequestValue(snapshot?.get(AudioQualityKey))
        val ageGate = snapshot?.get(AllowAgeRestrictedKey) ?: false
        val preferred = preferredClient(snapshot)
        val order = streamClientOrder(preferred, lastSuccessful, ageGate)
        val session = signIn()
        // The account's own visitor identity travels with its cookie; a fetched visitor id belongs to no account.
        val visitorData = session?.visitorData ?: visitor()
        // One BotGuard mint per resolve, as the shipped chain does for its main client: the pair below then
        // fills the web clients' serviceIntegrityDimensions and pot= like PlaybackAuthState does.
        val minted = poTokens?.mintForPlayback(videoId, session, visitorData)
        val region = locale().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
        val language = locale().toLanguageTag().ifBlank { "en" }
        val failed = linkedSetOf<String>()
        val botBlocked = linkedSetOf<String>()
        var loginRequired: String? = null
        var lastFailure: Throwable? = null
        for (attemptQuality in qualityAttempts(quality)) {
            for (client in order) {
                if (client.loginRequired && session == null) continue
                if (client.key in failed) continue
                val response = try {
                    requestYouTubePlayer(
                        http, videoId, null, client, null, visitorData, region, language, session,
                        poToken = poTokens?.tokenFor(client.clientName, minted),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                    failed += client.key
                    continue
                }
                val status = response.playabilityStatus()
                val reason = response.playabilityReason()
                if (!status.equals("OK", ignoreCase = true)) {
                    when {
                        isBotDetectionReason(reason) -> botBlocked += client.key
                        isLoginRecoveryResponse(status, reason) -> loginRequired = reason
                    }
                    failed += client.key
                    continue
                }
                val format = rankAudioFormats(response.audioFormats(), attemptQuality, networkMetered = false)
                    .firstOrNull { it.url != null }
                if (format == null) {
                    // Only ciphered formats; another client may still answer with direct URLs.
                    continue
                }
                lastSuccessful = client
                val details = response.videoDetails()
                return PreparedAudio.Progressive(
                    format.toDirectStream(
                        uri = appendPoTokenToStreamUrl(
                            requireNotNull(format.url),
                            poTokens?.tokenFor(client.clientName, minted),
                        ),
                        label = "YouTube ${client.friendlyName ?: client.clientName} ${format.bitrate / 1000} kbps",
                        title = details?.stringOf("title"),
                        author = details?.stringOf("author"),
                        durationMs = details?.stringOf("lengthSeconds")?.toLongOrNull()?.times(1_000L),
                        trusted = trusted,
                        userAgent = client.userAgent,
                    ),
                )
            }
        }
        if (loginRequired != null && botBlocked.isEmpty()) {
            // No client passed the sign-in or age gate with the session this request had, and no other tier can either.
            throw TerminalPlaybackAuthentication()
        }
        if (botBlocked.isNotEmpty() && botBlocked.size == failed.size) {
            throw DesktopYouTubeBotDetectionException(videoId, botBlocked)
        }
        if (failed.isNotEmpty() && lastFailure != null) {
            throw DesktopYouTubeNoPlayableResponseException(videoId, failed, lastFailure)
        }
        return null
    }

    private fun preferredClient(snapshot: PreferenceSnapshot?): DesktopYouTubeClient {
        return when (snapshot?.manualPlaybackClient()?.uppercase(Locale.US)) {
            "ANDROID_VR" -> DesktopYouTubeClients.ANDROID_VR_1_65_10
            "IOS" -> DesktopYouTubeClients.IOS
            "TVHTML5" -> DesktopYouTubeClients.TVHTML5
            "ANDROID_MUSIC" -> DesktopYouTubeClients.ANDROID_MUSIC
            else -> DesktopYouTubeClients.MAIN_CLIENT
        }
    }
}

/**
 * The stream for one resolved YouTube format. [userAgent] is the requesting client's, because googlevideo checks it
 * against the one that minted the URL; a URL NewPipe's own extractor minted carries no client, so it goes without one.
 */
internal fun DesktopAudioFormat.toDirectStream(
    uri: String,
    label: String,
    title: String?,
    author: String?,
    durationMs: Long?,
    trusted: Boolean,
    userAgent: String?,
): DirectStream =
    DirectStream(
        uri = uri,
        mimeType = mimeBase(mimeType),
        codecs = mimeCodecs(mimeType),
        contentLength = contentLength,
        label = label,
        source = AudioSourceType.YOUTUBE,
        matchedTitle = title,
        matchedArtist = author?.removeSuffix(" - Topic"),
        matchedDurationMs = durationMs,
        trustedDirectId = trusted,
        sampleRate = audioSampleRate,
        headers = userAgent?.let { PlaybackHeaders.of(mapOf("User-Agent" to it)) } ?: PlaybackHeaders.NONE,
    )
