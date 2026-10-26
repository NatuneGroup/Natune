package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.provider.YouTubeBrowse
import moe.rukamori.archivetune.shared.data.provider.YouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.YouTubeResolutionPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.settings.AllowAgeRestrictedKey
import moe.rukamori.archivetune.shared.domain.settings.AudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerStreamClientKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * The desktop YouTube terminal tier over [DesktopYouTubeClients]. It walks the shipped quality ladder and client order
 * as a visitor and returns the first ranked audio format that carries a direct URL.
 *
 * Ciphered formats (`signatureCipher`/`cipher`) are skipped: the base.js deobfuscation of the Android core is not ported
 * to this host, and a URL without its signature would only fail later in the engine. The clients that answer visitors
 * with direct URLs (visionOS, Android VR) are in the fallback set, so most tracks still resolve.
 *
 * InnerTubeX, NewPipe and Echo are Android-only extractor tiers. [fallback] answers null for each, which the shared
 * failsafe walk reads as a failed tier, so the chain ends honestly instead of inventing a stream.
 */
internal class DesktopYouTubeResolver(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val search: YouTubeBrowseTransport? = null,
    private val locale: () -> Locale = { Locale.getDefault() },
) : YouTubeResolutionPort {
    private val visitorLock = Mutex()
    private var visitorData: String? = null
    private var visitorFetched = false

    @Volatile
    private var lastSuccessful: DesktopYouTubeClient? = null

    override suspend fun primary(request: AudioResolveRequest): PreparedAudio? {
        val direct = request.directIds[AudioSourceType.YOUTUBE] ?: request.mediaId.takeIf { VIDEO_ID.matches(it) }
        if (direct != null) return resolveVideo(direct, trusted = true)
        val found = searchVideoId(request) ?: return null
        return resolveVideo(found, trusted = false)
    }

    override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? = null

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
        val visitorData = visitor()
        val region = locale().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
        val language = locale().toLanguageTag().ifBlank { "en" }
        val failed = linkedSetOf<String>()
        val botBlocked = linkedSetOf<String>()
        var loginRequired: String? = null
        var lastFailure: Throwable? = null
        for (attemptQuality in qualityAttempts(quality)) {
            for (client in order) {
                if (client.loginRequired) continue
                if (client.key in failed) continue
                val response = try {
                    requestYouTubePlayer(http, videoId, null, client, null, visitorData, region, language)
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
                return PreparedAudio.Progressive(format.toStream(client, response.videoDetails(), trusted))
            }
        }
        if (loginRequired != null && botBlocked.isEmpty()) {
            // A visitor cannot pass a sign-in or age gate, and no other tier can either.
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
        if (snapshot?.get(AutoChoosePlaybackClientKey) ?: true) return DesktopYouTubeClients.MAIN_CLIENT
        return when (snapshot?.get(PlayerStreamClientKey)?.uppercase(Locale.US)) {
            "ANDROID_VR" -> DesktopYouTubeClients.ANDROID_VR_1_65_10
            "IOS" -> DesktopYouTubeClients.IOS
            "TVHTML5" -> DesktopYouTubeClients.TVHTML5
            "ANDROID_MUSIC" -> DesktopYouTubeClients.ANDROID_MUSIC
            else -> DesktopYouTubeClients.MAIN_CLIENT
        }
    }
}

private fun DesktopAudioFormat.toStream(
    client: DesktopYouTubeClient,
    details: kotlinx.serialization.json.JsonObject?,
    trusted: Boolean,
): DirectStream {
    val title = details?.stringOf("title")
    val author = details?.stringOf("author")?.removeSuffix(" - Topic")
    val lengthMs = details?.stringOf("lengthSeconds")?.toLongOrNull()?.times(1_000L)
    return DirectStream(
        uri = requireNotNull(url),
        mimeType = mimeBase(mimeType),
        codecs = mimeCodecs(mimeType),
        contentLength = contentLength,
        label = "YouTube ${client.friendlyName ?: client.clientName} ${bitrate / 1000} kbps",
        source = AudioSourceType.YOUTUBE,
        matchedTitle = title,
        matchedArtist = author,
        matchedDurationMs = lengthMs,
        trustedDirectId = trusted,
        sampleRate = audioSampleRate,
        // googlevideo checks the requesting client's user agent against the one that minted the URL.
        headers = PlaybackHeaders.of(mapOf("User-Agent" to client.userAgent)),
    )
}
