package moe.rukamori.archivetune.desktop.provider

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientLifecycle
import com.metrolist.innertubex.extraction.strategy.ClientSelectionMode
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.DIRECT_ROUTE_SCOPE
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
import moe.rukamori.archivetune.shared.domain.settings.PlaybackClientChoice
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.manualPlaybackClientChoice
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private const val SEARCHED_ID_CACHE_SIZE = 64

/**
 * The desktop YouTube terminal tier over [DesktopYouTubeClients]. It walks the shipped quality ladder and client order
 * and returns the first ranked audio format it can turn into a playable URL. With a personal sign-in from [signIn], the
 * cookie-capable clients ask as that account and the login-only clients join the walk, as the shipped resolver lets
 * them for a signed-in user. Without one every request is a visitor request.
 *
 * The walk leads with the token-free clients when no PoToken was minted, states the player's signatureTimestamp for the
 * clients that ask for it, and deciphers `signatureCipher`/`cipher` formats and rewrites the throttling `n` parameter
 * through [cipher] — the same NewPipe JavaScript-player path the NewPipe tier uses. A ciphered format is therefore no
 * longer dropped, and an untransformed URL is no longer handed to the engine.
 *
 * [fallback] runs the shipped extractor tiers in the order the shared failsafe walk gives them: InnerTubeX through
 * [DesktopInnerTubeXTier], then NewPipe through [DesktopNewPipeTier]. Echo runs on Android and iOS only; the desktop has
 * no Echo port, so its tier answers null, which the walk reads as a failed tier. Every tier
 * writes one diagnostic line with its outcome.
 */
internal class DesktopYouTubeResolver(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val search: YouTubeBrowseTransport? = null,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val signIn: () -> DesktopYouTubeSignIn? = { null },
    private val poTokens: DesktopPoTokenOwner? = null,
    private val cipher: DesktopYouTubeStreamCipher = NewPipeDesktopYouTubeStreamCipher(),
    private val innerTubeX: DesktopInnerTubeXTier = DesktopInnerTubeXTier(http, tokenProvider = poTokens?.tokenProvider),
    private val newPipe: DesktopNewPipeTier = DesktopNewPipeTier(
        http,
        locale,
        mint = { videoId, visitorData -> poTokens?.mintForPlayback(videoId, signIn(), visitorData) },
    ),
    /**
     * Resolutions this process may hand out again. A googlevideo URL is bound to the video, the quality, the account
     * and the route that minted it, and it expires on its own, so the cache is keyed on those and honours the window.
     */
    private val cache: DesktopResolvedStreamCache = DesktopResolvedStreamCache(),
    /**
     * The routing identity the cache keys on: the direct route, the configured proxy, or null when the session cannot
     * name the egress a cached URL would be read over. A null scope disables caching for the resolution.
     */
    private val networkScope: () -> String? = { DIRECT_ROUTE_SCOPE },
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
        val key = cacheKey(target.videoId)
        // A refresh the queue asked for after a rejection must never read the entry it is replacing, and it must not
        // leave that entry behind either: the retry either answers with a newer URL or with nothing, and a stale URL
        // surviving a refresh that resolved nothing would come straight back on the next ordinary attempt.
        if (request.forceFreshStream) {
            cache.invalidate(target.videoId)
        } else {
            cache.get(key)?.let { cached ->
                // The line Android writes for a tier that answered, with `cache` naming why no request was made.
                logTier("shipped", true, started, "cache")
                return cached
            }
        }
        return try {
            resolvePrimary(target.videoId, target.trusted)?.also { resolved ->
                if (resolved is PreparedAudio.Progressive) cache.put(key, resolved)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A rejected video keeps none of its old URLs: the next attempt resolves fresh before it trusts anything.
            cache.invalidate(target.videoId)
            // Android logs the shipped tier only when it failed; a resolved primary writes nothing.
            logTier("shipped", false, started, failure.javaClass.simpleName)
            throw failure
        }
    }

    /**
     * InnerTubeX answers first, with its own content-aware client walk and health tracker; the hand-rolled client walk
     * below is the fallback and runs on whatever budget the first tier left. That order is the point of the port: the
     * library's selection is the one Metrolist proves, and the Natune walk that used to lead it cannot demote a failing
     * client and re-walks six of them per track.
     */
    private suspend fun resolvePrimary(videoId: String, trusted: Boolean): PreparedAudio? {
        val started = System.nanoTime()
        innerTubeXPrimary(videoId, trusted)?.let { return it }
        val spentMs = elapsedMs(started)
        if (spentMs >= PRIMARY_TOTAL_BUDGET_MS) {
            logTier(CLIENT_WALK_TIER, false, started, "the InnerTubeX tier spent the walk's budget")
            return null
        }
        return resolveVideo(videoId, trusted)
    }

    /** The InnerTubeX attempt, bounded so a cold watch-page fetch cannot spend the caller's whole resolve budget. */
    private suspend fun innerTubeXPrimary(videoId: String, trusted: Boolean): PreparedAudio? {
        val started = System.nanoTime()
        val quality = DesktopYouTubeAudioQuality.fromRequestValue(preferences()?.get(AudioQualityKey))
        val audio = try {
            withTimeoutOrNull(INNERTUBEX_PRIMARY_BUDGET_MS) {
                innerTubeX.resolve(videoId, quality, trusted, contentHints())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (auth: TerminalPlaybackAuthentication) {
            logTier(INNERTUBEX_TIER, false, started, auth.javaClass.simpleName)
            throw auth
        } catch (failure: Throwable) {
            // A refused transport shape or a library failure is not fatal: the client walk below may still answer.
            logTier(INNERTUBEX_TIER, false, started, failure.javaClass.simpleName)
            return null
        }
        logTier(INNERTUBEX_TIER, audio != null, started, if (audio == null) "timeout-or-no-stream" else null)
        return audio
    }

    /**
     * The hints one request carries: the manual client the settings page pinned, when InnerTubeX ships a profile that
     * can honour it (see [innerTubeXOverrideId]). The content facts stay null because a resolution only knows the video
     * id here; the walk then treats the content as ordinary and lets the player response say what it is.
     */
    private fun contentHints(): ContentHints =
        defaultContentHints(playbackClientOverrideId = innerTubeXOverrideId(preferences()?.manualPlaybackClientChoice()))

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

    /**
     * The cache identity of one resolution: everything a googlevideo URL is bound to. The quality is the ladder the
     * request asked for, the session is the account that minted it, and the network scope is the route it was minted
     * over — null while no single route can be named, which the cache reads as "do not store this". Neither the session
     * id nor a proxy endpoint is secret, and neither is logged.
     */
    private fun cacheKey(videoId: String): DesktopResolvedStreamCache.Key =
        DesktopResolvedStreamCache.Key(
            videoId = videoId,
            quality = DesktopYouTubeAudioQuality.fromRequestValue(preferences()?.get(AudioQualityKey)).name,
            session = sessionScope(),
            network = networkScope(),
        )

    /** The account a resolution belongs to. A visitor request and a signed-in one never share a URL. */
    private fun sessionScope(): String {
        val session = signIn() ?: return "anonymous"
        val account = session.dataSyncId?.takeIf(String::isNotBlank) ?: return "session"
        return "session:$account"
    }

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

    private suspend fun resolveVideo(videoId: String, trusted: Boolean): PreparedAudio? =
        // The walk is bounded on its own, so a bot-checked network surfaces in seconds and the extractor tiers still
        // get their turn inside the caller's budget instead of waiting for one exhausted client ladder.
        withTimeoutOrNull(PRIMARY_WALK_BUDGET_MS) { walkClients(videoId, trusted) }

    private suspend fun walkClients(videoId: String, trusted: Boolean): PreparedAudio? {
        val snapshot = preferences()
        val quality = DesktopYouTubeAudioQuality.fromRequestValue(snapshot?.get(AudioQualityKey))
        val ageGate = snapshot?.get(AllowAgeRestrictedKey) ?: false
        val choice = snapshot?.manualPlaybackClientChoice()
        val preferred = preferredClient(choice)
        val manualClient = choice != null
        val session = signIn()
        // The account's own visitor identity travels with its cookie; a fetched visitor id belongs to no account.
        val visitorData = session?.visitorData ?: visitor()
        // A cold BotGuard start must not eat the resolution budget: a timed-out mint leaves the walk untokened, and
        // the token-free clients lead it precisely because that is the common case on a bot-checking network.
        val minted =
            try {
                withTimeoutOrNull(MINT_BUDGET_MS) { poTokens?.mintForPlayback(videoId, session, visitorData) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val order =
            streamClientOrder(
                preferred,
                lastSuccessful,
                ageGate,
                hasPoToken = minted != null,
                manualClient = manualClient,
            )
        val region = locale().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
        val language = locale().toLanguageTag().ifBlank { "en" }
        val failed = linkedSetOf<String>()
        val botBlocked = linkedSetOf<String>()
        var loginRequired: String? = null
        var lastFailure: Throwable? = null
        var requests = 0
        var timestampResolved = false
        var timestamp: Int? = null
        for (client in order) {
            if (requests >= MAX_PLAYER_REQUESTS) break
            if (client.loginRequired && session == null) continue
            if (client.key in failed) continue
            // One player timestamp covers the walk: every client asks the same player version, so reading it once
            // keeps the JavaScript read off the per-client path after the first use.
            val clientTimestamp =
                if (client.useSignatureTimestamp) {
                    if (!timestampResolved) {
                        timestampResolved = true
                        timestamp = cipher.signatureTimestamp(videoId)
                    }
                    timestamp
                } else {
                    null
                }
            requests++
            val response =
                try {
                    requestYouTubePlayer(
                        http, videoId, null, client, clientTimestamp, visitorData, region, language, session,
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
            val details = response.videoDetails()
            // One ranking per response: the requested quality already ranks every codec and bitrate, so a lower
            // quality retry of the same client would only repeat the same answer.
            for (format in rankAudioFormats(response.audioFormats(), quality, networkMetered = false)) {
                val url = cipher.playableUrl(videoId, format.url, format.signatureCipher, format.cipher) ?: continue
                lastSuccessful = client
                return PreparedAudio.Progressive(
                    format.toDirectStream(
                        uri = appendPoTokenToStreamUrl(
                            url,
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
            // Every format was ciphered and the player could not decipher one. Another client may still answer.
            failed += client.key
        }
        if (botBlocked.isNotEmpty() && minted != null) {
            // The token the network rejected should not be reused for the next track; the next resolve re-mints.
            try {
                poTokens?.invalidateAttestation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed invalidation only leaves the old mint in place; the next resolve still tries a fresh one.
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

    /** The client the walk leads with for a hand-pinned choice; the automatic order when nothing is pinned. */
    private fun preferredClient(choice: PlaybackClientChoice?): DesktopYouTubeClient =
        when (choice) {
            PlaybackClientChoice.ANDROID_VR -> DesktopYouTubeClients.ANDROID_VR_1_65_10
            // Web Remix is the main client, so the pinned choice and the automatic lead are the same request.
            PlaybackClientChoice.WEB_REMIX -> DesktopYouTubeClients.MAIN_CLIENT
            PlaybackClientChoice.IOS -> DesktopYouTubeClients.IOS
            PlaybackClientChoice.TVHTML5 -> DesktopYouTubeClients.TVHTML5
            PlaybackClientChoice.ANDROID_MUSIC -> DesktopYouTubeClients.ANDROID_MUSIC
            null -> DesktopYouTubeClients.MAIN_CLIENT
        }

    private companion object {
        /**
         * The whole client walk's bound. The caller allows 30 s and then two 12 s extractor tiers; capping the walk
         * here means a bot-checked network reports its failure after this, not after the client ladder and every
         * quality retry burned the caller's budget.
         */
        const val PRIMARY_WALK_BUDGET_MS = 15_000L

        /**
         * InnerTubeX's share of the caller's 30 s. Its own extraction budget is 30 s, which would leave the fallback
         * walk nothing; a resolve that has not answered in this long is handed to the walk instead.
         */
        const val INNERTUBEX_PRIMARY_BUDGET_MS = 12_000L

        /** The whole primary resolution: InnerTubeX first, then the walk, inside the caller's 30 s. */
        const val PRIMARY_TOTAL_BUDGET_MS = 28_000L

        /** Player requests per walk. Six covers the token-free clients, WEB_REMIX and the two fallback web clients. */
        const val MAX_PLAYER_REQUESTS = 6

        /** A cold BotGuard mint may need the WebView/QuickJS boot; it leaves the rest of the walk its time. */
        const val MINT_BUDGET_MS = 4_000L

        /** The diagnostic names of the two tiers of the primary resolution, distinct from the fallback tier lines. */
        const val INNERTUBEX_TIER = "innertubex-primary"
        const val CLIENT_WALK_TIER = "client-walk"
    }
}

/**
 * The InnerTubeX catalog id that stands for one settings client choice. The ids are the library's own profile ids, and
 * every client the playback-client setting offers has one here, so the mapping is total: [innerTubeXOverrideId] then
 * asks the library's catalog whether that profile can in fact answer a user's playback.
 */
internal fun PlaybackClientChoice.innerTubeXCatalogId(): String = when (this) {
    PlaybackClientChoice.ANDROID_VR -> "ANDROID_VR_1_65_10"
    PlaybackClientChoice.WEB_REMIX -> "WEB_REMIX"
    PlaybackClientChoice.IOS -> "IOS"
    PlaybackClientChoice.TVHTML5 -> "TVHTML5"
    PlaybackClientChoice.ANDROID_MUSIC -> "ANDROID_MUSIC"
}

/**
 * The client override one request carries, or null for InnerTubeX's automatic walk.
 *
 * The library's shipped catalog is the authority on what a pinned client may do, and it is read rather than trusted from
 * this file: a profile it no longer lists, marks broken, or keeps back for its own probes cannot answer playback, and
 * pinning one would make the first tier spend its single player request on a client that is known to fail and hide the
 * walk that would have worked. Every choice the setting offers therefore ends in exactly one of two answers — the id of
 * a usable profile, or null with the catalog naming why not — and `innerTubeXCatalogId` is the only place an id is
 * spelled out.
 */
internal fun innerTubeXOverrideId(choice: PlaybackClientChoice?): String? {
    val id = choice?.innerTubeXCatalogId() ?: return null
    val manifest = PlaybackClientCatalog.find(id)?.manifest ?: return null
    if (manifest.lifecycle == ClientLifecycle.BROKEN) return null
    if (manifest.selectionMode == ClientSelectionMode.PROBE_ONLY) return null
    return id
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
        // A googlevideo URL states its own authorization window; without it the locator would look reusable forever.
        expiresAtEpochMs = youTubeStreamExpiryMs(uri),
    )
