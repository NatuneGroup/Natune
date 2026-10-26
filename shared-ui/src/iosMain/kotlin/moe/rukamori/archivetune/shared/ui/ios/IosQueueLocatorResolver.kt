package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPort
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.DeezerAccess
import moe.rukamori.archivetune.shared.data.provider.KtorDeezerRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzBackupRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorTidalRepository
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.QqMusicAccess
import moe.rukamori.archivetune.shared.data.provider.TidalAccess
import moe.rukamori.archivetune.shared.data.provider.IosJioSaavnUrlDecoder
import moe.rukamori.archivetune.shared.data.provider.KtorJioSaavnRepository
import moe.rukamori.archivetune.shared.data.provider.ProviderHttpFailure
import moe.rukamori.archivetune.shared.data.provider.YouTubeResolutionPort
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.TidalAccountFirstKey
import moe.rukamori.archivetune.shared.domain.settings.tidalCountryCode
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier
import moe.rukamori.archivetune.shared.domain.source.toPlaybackLocator
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest
import moe.rukamori.archivetune.shared.ui.host.unresolvedSourceReason
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope
import platform.Foundation.NSBundle
import platform.Foundation.NSUUID

/** The local-file provider id. A local song has no provider to search, so it is refused until a local owner exists. */
private const val LOCAL_PROVIDER = "local"

/**
 * Resolves one queue occurrence to the locator the iOS engine may open. The ordered source chain decides which provider
 * answers. An anonymous progressive or Deezer answer maps to its ordinary locator. A personal progressive, native or
 * TIDAL answer is admitted by [port] for that occurrence first, so its account is checked before any byte is read and
 * the queue's Load claims exactly that view. A refused answer sends the occurrence down the chain. When no source
 * answers, the load fails and names the attempts it made.
 */
internal class IosQueueLocatorResolver(
    private val audio: OrderedAudioResolver,
    private val port: IosPlaybackPort,
    private val preferences: () -> PreferenceSnapshot?,
) : QueueLocatorResolver {
    override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator {
        if (occurrence.item.ref.provider == LOCAL_PROVIDER) {
            throw IllegalStateException("local files are not connected on this iOS host")
        }
        val request = occurrence.toAudioResolveRequest(preferences())
        var refused = emptySet<AudioSourceType>()
        while (true) {
            val prepared = when (val resolution = audio.resolve(request, hostSourceSnapshot(preferences()), rejected = refused)) {
                is AudioResolution.Resolved -> resolution.audio
                is AudioResolution.Unavailable ->
                    throw IllegalStateException(unresolvedSourceReason(occurrence, resolution.attempts, host = "iOS"))
                is AudioResolution.NativeRoute -> error("the iOS resolver never requests a native route")
            }
            if (prepared is PreparedAudio.Progressive && !prepared.stream.isPersonal()) {
                return prepared.stream.toPlaybackLocator()
            }
            if (prepared is PreparedAudio.DeezerEncrypted) return prepared.toPlaybackLocator()
            when (val outcome = port.prepareForQueue(occurrence.occurrenceId, prepared)) {
                is IosPlaybackPort.QueuedPreparation.Ready -> return outcome.locator
                is IosPlaybackPort.QueuedPreparation.Refused -> refused = refused + prepared.source
            }
        }
    }
}

private fun DirectStream.isPersonal(): Boolean =
    accountScope != null && accountScope != ANONYMOUS_ACCOUNT_SCOPE

/**
 * The YouTube tier of this host. The in-process YouTube extractor tiers run on the JVM and Android only, so no YouTube
 * stream is resolved here: every attempt answers no match, and the resolver reports YouTube as an unavailable attempt
 * rather than guessing a URL.
 */
internal object IosYouTubeUnavailable : YouTubeResolutionPort {
    override suspend fun primary(request: AudioResolveRequest): PreparedAudio? = null

    override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? = null
}

/**
 * The audio providers this host connects, over the one HTTP client of the graph. TIDAL, Deezer and QQ read the personal
 * account leases of [accounts] on every resolution, so a sign-out or a replaced account stops them at once. Qobuz uses
 * the public backup mirror. The Playback Pool feeds pooled TIDAL and Deezer accounts plus community instances while
 * its switch is on. Apple, Amazon and Spotify stay out of this list on iOS; a missing source answers as an
 * unavailable attempt.
 */
internal fun iosAudioProviders(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    tidalSessionRefresh: TidalSessionRefresh?,
    pool: IosPlaybackPool? = null,
): List<AudioProviderRepository> = listOfNotNull(
    IosTidalSource(http, accounts, preferences, tidalSessionRefresh, pool),
    KtorQobuzBackupRepository(http),
    IosDeezerSource(http, accounts, pool),
    IosQqMusicSource(http, accounts),
    iosJioSaavnSource(http),
)

/**
 * JioSaavn over the shared repository and the CommonCrypto DES decoder. The decoder key is host-supplied material: it
 * is read from the `ArchiveTuneJioSaavnDesKey` Info.plist value, and the source is not composed without it, so it
 * answers as unavailable instead of shipping a copied key.
 */
private fun iosJioSaavnSource(http: HttpClient): AudioProviderRepository? {
    val key = (NSBundle.mainBundle.objectForInfoDictionaryKey("ArchiveTuneJioSaavnDesKey") as? String)
        ?.trim()?.encodeToByteArray()?.takeIf { it.size == 8 } ?: return null
    return KtorJioSaavnRepository(http, IosJioSaavnUrlDecoder(key))
}

/**
 * TIDAL over the personal account lease, the Playback Pool accounts of [pool] while its switch is on and the HiFi
 * instances (the user's own then the pool's discovered ones), in the shipped order: the accounts first while
 * "account first" is on (the default), otherwise the instances first. Attempts run in that order; the Android
 * resolver races pool accounts, which this host does not do for latency. A terminal authentication failure of a
 * personal token stops the source; a pooled account that is refused is reported dead and the next one is tried.
 */
private class IosTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val sessionRefresh: TidalSessionRefresh?,
    private val pool: IosPlaybackPool? = null,
) : AudioProviderRepository {
    override val source = AudioSourceType.TIDAL

    private class Attempt(val repository: KtorTidalRepository, val poolId: Long? = null, val pooled: Boolean = false)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences()
        val country = snapshot?.tidalCountryCode()?.takeIf { it.length == 2 } ?: "US"
        val personal = personalRepository(country)?.let { Attempt(it) }
        val pooled = pool?.tidalAccounts().orEmpty().map { account ->
            Attempt(
                KtorTidalRepository(
                    http,
                    TidalAccess(
                        scope = "PLAYBACK_POOL:tidal:${account.id ?: 0L}",
                        bearer = account.token,
                        credentialScope = CredentialScope.PLAYBACK_POOL,
                        isActive = { pool?.enabled() == true },
                    ),
                    countryCode = account.countryCode?.trim()?.takeIf { it.length == 2 } ?: "US",
                ),
                poolId = account.id,
                pooled = true,
            )
        }
        val instanceBases = LinkedHashSet<String>().apply {
            addAll(snapshot?.tidalInstanceList().orEmpty())
            addAll(pool?.tidalInstances().orEmpty())
        }
        val instances = instanceBases.map { base ->
            Attempt(KtorTidalRepository(http, TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE), instanceBase = base, countryCode = country))
        }
        val accountFirst = snapshot?.get(TidalAccountFirstKey) ?: true
        val accountAttempts = listOfNotNull(personal) + pooled
        val attempts = if (accountFirst) accountAttempts + instances else instances + accountAttempts
        var failure: Exception? = null
        for (attempt in attempts) {
            try {
                attempt.repository.resolve(request)?.let { resolved ->
                    if (attempt.pooled) pool?.noteSuccess(IosPlaybackPool.TIDAL, attempt.poolId)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                if (!attempt.pooled) throw auth
                pool?.report(IosPlaybackPool.TIDAL, attempt.poolId, "dead")
                pool?.noteFailure(IosPlaybackPool.TIDAL, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    if ((error as? ProviderHttpFailure)?.status == 401) pool?.report(IosPlaybackPool.TIDAL, attempt.poolId, "dead")
                    pool?.noteFailure(IosPlaybackPool.TIDAL, attempt.poolId)
                }
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }

    private suspend fun personalRepository(country: String): KtorTidalRepository? {
        val scope = providerAccountScope(ContentProvider.TIDAL.id, CredentialScope.PERSONAL) ?: return null
        var lease = accounts.personalTidalLease(scope) ?: return null
        if (sessionRefresh?.refreshIfDue(lease) == true) lease = accounts.personalTidalLease(scope) ?: return null
        return KtorTidalRepository(
            http,
            TidalAccess(
                scope = lease.accountScope,
                bearer = lease.accessToken.reveal(),
                credentialScope = CredentialScope.PERSONAL,
                isActive = lease::isStillAdmitted,
            ),
            countryCode = country,
        )
    }
}

/**
 * Deezer over the personal ARL lease, then the Playback Pool ARLs. Deezer media needs a signed-in session, so with
 * neither the source answers no match. One repository is kept per lease generation, so its gateway session is reused
 * until the account changes; pooled ARLs get one repository per ARL for the same reason.
 */
private class IosDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val pool: IosPlaybackPool? = null,
) : AudioProviderRepository {
    override val source = AudioSourceType.DEEZER
    private var cached: Pair<Long, KtorDeezerRepository>? = null
    private val pooledRepositories = HashMap<String, KtorDeezerRepository>()

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalDeezerLease(it) } ?: return resolvePooled(request)
        val repository = cached?.takeIf { it.first == lease.generation }?.second ?: KtorDeezerRepository(
            http,
            nowMs = ::iosNowMillis,
            access = DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted),
        ).also { cached = lease.generation to it }
        return repository.resolve(request)
    }

    private suspend fun resolvePooled(request: AudioResolveRequest): PreparedAudio? {
        val owner = pool ?: return null
        var failure: Exception? = null
        for (account in owner.deezerAccounts()) {
            val repository = pooledRepositories.getOrPut(account.arl) {
                KtorDeezerRepository(
                    http,
                    nowMs = ::iosNowMillis,
                    access = DeezerAccess(
                        "PLAYBACK_POOL:deezer:${account.id ?: 0L}",
                        account.arl,
                        CredentialScope.PLAYBACK_POOL,
                        isActive = { owner.enabled() },
                    ),
                )
            }
            try {
                repository.resolve(request)?.let { resolved ->
                    owner.noteSuccess(IosPlaybackPool.DEEZER, account.id)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                owner.report(IosPlaybackPool.DEEZER, account.id, "dead")
                owner.noteFailure(IosPlaybackPool.DEEZER, account.id)
            } catch (error: Exception) {
                owner.noteFailure(IosPlaybackPool.DEEZER, account.id)
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }
}

/** QQ Music with the personal ticket when one is accepted, otherwise the anonymous RPC the repository also supports. */
private class IosQqMusicSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
) : AudioProviderRepository {
    override val source = AudioSourceType.QQ
    private val guid = NSUUID().UUIDString.replace("-", "").lowercase()

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val scope = providerAccountScope(ContentProvider.QQ.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalQqMusicLease(it) }
        val access = lease?.let {
            QqMusicAccess(it.accountScope, it.uin, it.musicKey.reveal(), it.loginType, CredentialScope.PERSONAL, it::isStillAdmitted)
        }
        return KtorQqMusicRepository(http, access, guid = { guid }).resolve(request)
    }
}
