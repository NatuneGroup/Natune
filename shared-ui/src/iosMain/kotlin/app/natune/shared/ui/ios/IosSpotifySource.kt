package app.natune.shared.ui.ios

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.natune.shared.data.platform.ios.iosBuildSetting
import app.natune.shared.data.provider.KtorSpotifyAuth
import app.natune.shared.data.provider.spotify.SpotifyEchoClient
import app.natune.shared.data.provider.spotify.SpotifyEchoConfiguration
import app.natune.shared.data.provider.spotify.spotifyProfileId
import app.natune.shared.domain.platform.CredentialScope
import app.natune.shared.domain.platform.PlatformSecret
import app.natune.shared.domain.platform.ProviderAccountCommitResult
import app.natune.shared.domain.platform.ProviderIdentity
import app.natune.shared.domain.repository.AudioProviderRepository
import app.natune.shared.domain.repository.AudioProviderUnavailable
import app.natune.shared.domain.repository.AudioProviderUnavailableReason
import app.natune.shared.domain.repository.AudioPurpose
import app.natune.shared.domain.repository.AudioResolveRequest
import app.natune.shared.domain.repository.PreparedAudio
import app.natune.shared.domain.settings.*
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.SpotifyStreamEngine
import app.natune.shared.domain.source.SpotifyStreamingPolicy
import app.natune.shared.ui.settings.VaultProviderAccountStore
import app.natune.shared.ui.settings.providerAccountPending

/** Echo uses only the dedicated streaming cookie; catalog credentials enter the collision check alone. */
internal class IosSpotifySource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.SPOTIFY
    private val lock = Mutex()
    private val client: SpotifyEchoClient? by lazy {
        val id = iosBuildSetting("ArchiveTuneSpotifyDesktopClientId")
        val iv = iosBuildSetting("ArchiveTuneSpotifyAudioIv")
        if (id.isBlank() || iv.length != 32 || iv.any { it.digitToIntOrNull(16) == null }) null
        else SpotifyEchoClient(http, SpotifyEchoConfiguration(id, iosBuildSetting("ArchiveTuneSpotifyWebClientId"), iv))
    }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = lock.withLock {
        fun unavailable(reason: AudioProviderUnavailableReason): Nothing = throw AudioProviderUnavailable(reason)
        val prefs = preferences() ?: unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        if (request.purpose == AudioPurpose.DOWNLOAD || prefs[SpotifyStreamEnabledKey] != true)
            unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        if (prefs.spotifyStreamEngineEffective() != SpotifyStreamEngine.UNOFFICIAL ||
            prefs.spotifyStreamResolveMethod() != SpotifyStreamResolveMethod.ECHO)
            unavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        val transport = client ?: unavailable(AudioProviderUnavailableReason.CLIENT_CONFIGURATION_REQUIRED)
        val cookie = prefs[SpotifyStreamSpDcKey].orEmpty()
        val user = prefs[SpotifyStreamUserIdKey].orEmpty()
        val catalogCookie = prefs[SpotifySpDcKey].orEmpty()
        if (cookie.isBlank() || user.isBlank() || cookie == catalogCookie)
            unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        fun active(): Boolean = preferences()?.let {
            it[SpotifyStreamEnabledKey] == true && it.spotifyStreamEngineEffective() == SpotifyStreamEngine.UNOFFICIAL &&
                it.spotifyStreamResolveMethod() == SpotifyStreamResolveMethod.ECHO &&
                it[SpotifyStreamSpDcKey] == cookie && it[SpotifyStreamUserIdKey] == user &&
                it[SpotifySpDcKey].orEmpty() == catalogCookie
        } == true
        if (transport.userId(cookie, ::active) != user || !active())
            unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        val catalogId = if (catalogCookie.isBlank()) null else {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val token = prefs[SpotifyAccessTokenKey]?.takeIf { (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) > now }
                ?: KtorSpotifyAuth(http).fetchAccessToken(catalogCookie, prefs[SpotifySpKeyKey].orEmpty(), { now })
                    .getOrNull()?.takeUnless { it.isAnonymous }?.accessToken
            token?.let { spotifyProfileId(http, it) }
        }
        if (!active() || SpotifyStreamingPolicy.streamingStandsDown(catalogCookie, cookie, user) { catalogId })
            unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        val scope = CredentialScope.SPOTIFY_STREAMING
        val identity = ProviderIdentity("spotify", scope, user)
        if (accounts.acceptedAccount("spotify", scope.name)?.identity != identity) {
            val generation = accounts.beginAttempt("spotify", scope)
            if (!active()) unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
            val pending = providerAccountPending(identity, generation, mapOf(
                SpotifyStreamSpDcKey.name to PlatformSecret(cookie),
                SpotifyStreamSpKeyKey.name to PlatformSecret(prefs[SpotifyStreamSpKeyKey].orEmpty()),
                SpotifyStreamAccessTokenKey.name to PlatformSecret(prefs[SpotifyStreamAccessTokenKey].orEmpty()),
            ), emptyList())
            if (accounts.commit(pending) !is ProviderAccountCommitResult.Accepted || !active())
                unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        }
        transport.resolve(cookie, request, scope.name, ::active)
    }
}

internal fun iosSpotifyStreamingReady(prefs: PreferenceSnapshot?): Boolean = prefs != null &&
    prefs.spotifyStreamEngineEffective() == SpotifyStreamEngine.UNOFFICIAL &&
    prefs.spotifyStreamResolveMethod() == SpotifyStreamResolveMethod.ECHO &&
    !prefs[SpotifyStreamSpDcKey].isNullOrBlank() && !prefs[SpotifyStreamUserIdKey].isNullOrBlank() &&
    prefs[SpotifyStreamSpDcKey] != prefs[SpotifySpDcKey]
