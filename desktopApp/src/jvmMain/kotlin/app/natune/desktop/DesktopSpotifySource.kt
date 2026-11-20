package app.natune.desktop

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.natune.desktop.provider.DesktopSourceAdmissions
import app.natune.desktop.provider.DesktopSpotifyStreamingTransport
import app.natune.desktop.provider.desktopSpotifyProfileId
import app.natune.shared.domain.platform.CredentialScope
import app.natune.shared.domain.platform.ProviderIdentity
import app.natune.shared.domain.repository.AudioProviderRepository
import app.natune.shared.domain.repository.AudioProviderUnavailable
import app.natune.shared.domain.repository.AudioProviderUnavailableReason
import app.natune.shared.domain.repository.AudioPurpose
import app.natune.shared.domain.repository.AudioResolveRequest
import app.natune.shared.domain.repository.PreparedAudio
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.SpotifyAccessTokenExpiresAtKey
import app.natune.shared.domain.settings.SpotifyAccessTokenKey
import app.natune.shared.domain.settings.SpotifySpDcKey
import app.natune.shared.domain.settings.SpotifyStreamEnabledKey
import app.natune.shared.domain.settings.SpotifyStreamResolveMethod
import app.natune.shared.domain.settings.SpotifyStreamSpDcKey
import app.natune.shared.domain.settings.SpotifyStreamUserIdKey
import app.natune.shared.domain.settings.StringPreferenceKey
import app.natune.shared.domain.settings.spotifyStreamEngineEffective
import app.natune.shared.domain.settings.spotifyStreamResolveMethod
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.SpotifyStreamEngine
import app.natune.shared.domain.source.SpotifyStreamingPolicy

private val CatalogAcceptedId = StringPreferenceKey("account.spotify.SPOTIFY_CATALOG.id")

/** This is an account gate, not a promise that the selected desktop DRM method is available. */
internal fun desktopSpotifyStreamingReady(preferences: PreferenceSnapshot?): Boolean {
    val prefs = preferences ?: return false
    if (prefs.spotifyStreamEngineEffective() == SpotifyStreamEngine.LIBRESPOT) return DesktopSpotifyLibrespotAudio.ready(prefs)
    val cookie = prefs[SpotifyStreamSpDcKey].orEmpty()
    val user = prefs[SpotifyStreamUserIdKey].orEmpty()
    if (cookie.isBlank() || user.isBlank()) return false
    return !SpotifyStreamingPolicy.streamingStandsDown(prefs[SpotifySpDcKey].orEmpty(), cookie, user) {
        prefs[CatalogAcceptedId]
    }
}

internal class DesktopSpotifySource(
    http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val admissions: DesktopSourceAdmissions,
    private val transport: DesktopSpotifyStreamingTransport?,
    private val librespot: DesktopSpotifyLibrespotAudio? = null,
    private val catalogProfile: suspend (String) -> String? = { desktopSpotifyProfileId(http, it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AudioProviderRepository {
    override val source = AudioSourceType.SPOTIFY
    private val gate = Mutex()
    private var comparedSession: Pair<String, String?>? = null
    private var comparedUser: String? = null

    private class StreamingCredential(val cookie: String, val userId: String, val catalogCookie: String) {
        override fun equals(other: Any?): Boolean = other is StreamingCredential &&
            cookie == other.cookie && userId == other.userId && catalogCookie == other.catalogCookie
        override fun hashCode(): Int = 31 * cookie.hashCode() + userId.hashCode()
        override fun toString() = "StreamingCredential(REDACTED)"
    }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = gate.withLock {
        val prefs = preferences() ?: throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        if (request.purpose == AudioPurpose.DOWNLOAD || !desktopSpotifyStreamingReady(prefs) ||
            prefs[SpotifyStreamEnabledKey] != true) {
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        }
        if (prefs.spotifyStreamEngineEffective() == SpotifyStreamEngine.LIBRESPOT) {
            return@withLock librespot?.resolve(request)
                ?: throw AudioProviderUnavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        }
        // Preserve the saved method and default. Falling through is not permission to silently switch it to Echo.
        if (prefs.spotifyStreamResolveMethod() != SpotifyStreamResolveMethod.ECHO) {
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        }
        val client = transport
            ?: throw AudioProviderUnavailable(AudioProviderUnavailableReason.CLIENT_CONFIGURATION_REQUIRED)
        val captured = StreamingCredential(prefs[SpotifyStreamSpDcKey].orEmpty(), prefs[SpotifyStreamUserIdKey].orEmpty(),
            prefs[SpotifySpDcKey].orEmpty())
        fun current(): Boolean = preferences()?.let { now ->
            now.spotifyStreamEngineEffective() == SpotifyStreamEngine.UNOFFICIAL && desktopSpotifyStreamingReady(now) && now[SpotifyStreamEnabledKey] == true &&
                now.spotifyStreamResolveMethod() == SpotifyStreamResolveMethod.ECHO &&
                now[SpotifyStreamSpDcKey] == captured.cookie && now[SpotifyStreamUserIdKey] == captured.userId &&
                now[SpotifySpDcKey].orEmpty() == captured.catalogCookie
        } == true
        val actualUser = client.userId(captured.cookie, ::current)
        if (!current() || actualUser != captured.userId) {
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        }
        val catalogToken = prefs[SpotifyAccessTokenKey]?.takeIf {
            it.isNotBlank() && (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) > nowMs()
        }
        val comparison = captured.catalogCookie to catalogToken
        if (comparison != comparedSession) {
            comparedUser = prefs[CatalogAcceptedId] ?: catalogToken?.takeIf { captured.catalogCookie.isNotBlank() }
                ?.let { catalogProfile(it) }
            comparedSession = comparison
        }
        if (!current() || SpotifyStreamingPolicy.streamingStandsDown(captured.catalogCookie, captured.cookie,
                actualUser.orEmpty()) { comparedUser }) {
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        }
        val lease = admissions.accept(ProviderIdentity("spotify", CredentialScope.SPOTIFY_STREAMING,
            requireNotNull(actualUser)), captured, ::current)
            ?: throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        client.resolve(captured.cookie, request, lease.scope) { admissions.active(lease) }
    }

    suspend fun close() = gate.withLock {
        comparedSession = null
        comparedUser = null
        transport?.close()
        librespot?.close()
    }
}
