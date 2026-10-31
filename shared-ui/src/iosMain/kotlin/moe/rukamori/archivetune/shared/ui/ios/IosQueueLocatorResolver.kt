package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixAppleMusicToken
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPool
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPort
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.provider.AmazonInstanceAccess
import moe.rukamori.archivetune.shared.data.provider.AppleMusicAccess
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.DeezerAccess
import moe.rukamori.archivetune.shared.data.provider.IosJioSaavnUrlDecoder
import moe.rukamori.archivetune.shared.data.provider.IosQobuzRequestDigest
import moe.rukamori.archivetune.shared.data.provider.KtorAmazonRepository
import moe.rukamori.archivetune.shared.data.provider.KtorAppleMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorDeezerRepository
import moe.rukamori.archivetune.shared.data.provider.KtorJioSaavnRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzBackupRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorTidalRepository
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.ProviderHttpFailure
import moe.rukamori.archivetune.shared.data.provider.QobuzAccess
import moe.rukamori.archivetune.shared.data.provider.QqMusicAccess
import moe.rukamori.archivetune.shared.data.provider.TidalAccess
import moe.rukamori.archivetune.shared.data.provider.YouTubeResolutionPort
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.ProviderAccountCommitResult
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.settings.AmazonBypassTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AmazonTurnstileJwtExpiryMsKey
import moe.rukamori.archivetune.shared.domain.settings.AmazonTurnstileJwtKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicDevTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.TidalAccountFirstKey
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.settings.qobuzInstanceList
import moe.rukamori.archivetune.shared.domain.settings.qobuzTokens
import moe.rukamori.archivetune.shared.domain.settings.tidalCountryCode
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.YouTubeFallbackTier
import moe.rukamori.archivetune.shared.domain.source.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest
import moe.rukamori.archivetune.shared.ui.host.unresolvedSourceReason
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.playbackPoolPending
import moe.rukamori.archivetune.shared.ui.settings.poolScopeForFeedId
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope
import platform.Foundation.NSUUID

/** The local-file provider id. A local song has no provider to search: it plays the file its import owner named. */
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
    /** The imported file of a `local` song, or null when its file is gone. */
    private val localFile: (moe.rukamori.archivetune.shared.domain.repository.ContentRef) -> String? = { null },
    /** The stored file of a completed download, or null when the song is not a stored download. */
    private val downloadedFile: suspend (moe.rukamori.archivetune.shared.domain.repository.ContentRef) -> String? = { null },
) : QueueLocatorResolver {
    override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator {
        if (occurrence.item.ref.provider == LOCAL_PROVIDER) {
            val path = localFile(occurrence.item.ref)
                ?: throw IllegalStateException("the imported file of this local song is gone")
            return PlaybackLocator.LocalFile(path)
        }
        // A stored download plays from the app container before any provider is asked for it.
        downloadedFile(occurrence.item.ref)?.let { return PlaybackLocator.LocalFile(it) }
        // A Telegram song is a TDLib file the playback port streams itself; no provider chain can answer for it.
        if (occurrence.item.ref.provider == TelegramTrackRef.SCHEME) {
            val track = TelegramTrackRef.fromMediaId(occurrence.item.ref.id)
                ?: throw IllegalStateException("invalid Telegram media id: ${occurrence.item.ref.id}")
            return PlaybackLocator.TelegramFile(track)
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
 * account leases of [accounts] on every resolution, so a sign-out or a replaced account stops them at once; Qobuz,
 * Apple and Amazon read their preference credentials the same way. [pool] supplies the Playback Pool accounts and
 * discovered instances to TIDAL, Qobuz, Deezer, Apple and Amazon. JioSaavn needs the build's DES key to decode its
 * stream URLs, so it is composed only when the key exists. Spotify stays out of this list on iOS — there is no iOS
 * transport for its catalog or streaming transports — so it answers as an unavailable attempt.
 */
internal fun iosAudioProviders(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    tidalSessionRefresh: TidalSessionRefresh?,
    pool: IosPlaybackPool?,
): List<AudioProviderRepository> = listOfNotNull(
    IosTidalSource(http, accounts, preferences, tidalSessionRefresh, pool),
    KtorQobuzBackupRepository(http),
    IosDeezerSource(http, accounts, pool),
    IosQobuzSource(http, accounts, preferences, pool),
    IosAppleMusicSource(http, accounts, preferences, pool),
    IosAmazonSource(http, preferences, accounts, pool),
    iosJioSaavnSource(http),
    IosQqMusicSource(http, accounts),
)

/**
 * Commits one Playback Pool feed entry to the vault and returns the opaque account scope its streams carry, or null
 * when the store refused it. Pool entries are admitted only after they are committed here, so the scope never names an
 * account the vault cannot read back. A stale generation (a racing commit) is retried once with a fresh attempt.
 */
private suspend fun VaultProviderAccountStore.admitPoolEntry(
    provider: ContentProvider,
    feedId: Long,
    credentials: Map<String, String>,
): String? {
    repeat(2) {
        val attempt = beginPlaybackPoolAttempt(provider.id, feedId)
        val pending = playbackPoolPending(
            ProviderIdentity(provider.id, CredentialScope.PLAYBACK_POOL, "feed-$feedId"),
            attempt.generation,
            credentials.mapValues { PlatformSecret(it.value) },
            emptyList(),
        )
        when (commitPlaybackPoolEntry(attempt, pending)) {
            is ProviderAccountCommitResult.Accepted -> return poolScopeForFeedId(feedId)
            ProviderAccountCommitResult.StaleGeneration -> Unit
            is ProviderAccountCommitResult.Failed -> return null
            is ProviderAccountCommitResult.SameAccountRejected -> return null
        }
    }
    return null
}

/**
 * TIDAL over the personal account lease, the Playback Pool accounts and the HiFi instances (the user's own, then the
 * pool's discovered ones), in the shipped order: the accounts first while "account first" is on (the default),
 * otherwise the instances first. Attempts run in that order; the Android resolver races its attempts and this host
 * does not yet, which the binding table in ios-status.md names. A failed attempt moves to the next one. A terminal
 * authentication failure of the personal account stops the source; a pooled account that fails authentication or
 * answers HTTP 401 is reported dead to the pool and the next one is tried.
 */
private class IosTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val sessionRefresh: TidalSessionRefresh?,
    private val pool: IosPlaybackPool?,
) : AudioProviderRepository {
    override val source = AudioSourceType.TIDAL

    private class Attempt(val repository: KtorTidalRepository, val poolId: Long? = null, val pooled: Boolean = false)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences()
        val country = snapshot?.tidalCountryCode()?.takeIf { it.length == 2 } ?: "US"
        val personal = personalRepository(country)?.let { Attempt(it) }
        val pooled = pool?.tidalAccounts().orEmpty().mapNotNull { account ->
            val feedId = account.id?.takeIf { it > 0L } ?: return@mapNotNull null
            val scope = accounts.admitPoolEntry(
                ContentProvider.TIDAL, feedId, buildMap {
                    put("token", account.token)
                    account.refreshToken?.let { put("refreshToken", it) }
                    account.countryCode?.let { put("countryCode", it) }
                    put("premium", account.premium.toString())
                },
            ) ?: return@mapNotNull null
            Attempt(
                KtorTidalRepository(
                    http,
                    TidalAccess(scope = scope, bearer = account.token, credentialScope = CredentialScope.PLAYBACK_POOL, isActive = { pool?.enabled() == true }),
                    countryCode = account.countryCode?.trim()?.takeIf { it.length == 2 } ?: "US",
                ),
                poolId = feedId,
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
 * Deezer over the personal ARL lease. Deezer media needs a signed-in session, so without one the source answers no
 * match. One repository is kept per lease generation, so its gateway session is reused until the account changes.
 */
private class IosDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val pool: IosPlaybackPool?,
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
                val feedId = account.id?.takeIf { it > 0L }
                val scope = feedId?.let {
                    accounts.admitPoolEntry(
                        ContentProvider.DEEZER, it, buildMap {
                            put("arl", account.arl)
                            account.masterSecret?.let { secret -> put("masterSecret", secret) }
                            put("premium", account.premium.toString())
                        },
                    )
                } ?: ANONYMOUS_ACCOUNT_SCOPE
                KtorDeezerRepository(
                    http,
                    nowMs = ::iosNowMillis,
                    access = DeezerAccess(scope, account.arl, CredentialScope.PLAYBACK_POOL, isActive = { owner.enabled() }),
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

/**
 * Qobuz over the user's stored app-secret token pairs, then the Playback Pool accounts, then the user instances and the
 * pool's discovered ones. The account path needs the request signer ([IosQobuzRequestDigest]) for track and stream
 * calls; instances answer anonymously. A dead pooled token is reported and the next attempt is tried.
 */
private class IosQobuzSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val pool: IosPlaybackPool?,
) : AudioProviderRepository {
    override val source = AudioSourceType.QOBUZ

    private class Attempt(val repository: KtorQobuzRepository, val poolId: Long? = null, val pooled: Boolean = false)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences()
        val personalScope = providerAccountScope(ContentProvider.QOBUZ.id, CredentialScope.PERSONAL)
        val personal = snapshot?.qobuzTokens().orEmpty().filter { it.isComplete() && it.poolId == null }.mapNotNull { entry ->
            personalScope?.let {
                Attempt(
                    KtorQobuzRepository(
                        http,
                        nowMs = ::iosNowMillis,
                        access = QobuzAccess(it, entry.appId, entry.token, entry.appSecret, CredentialScope.PERSONAL) {
                            preferences()?.qobuzTokens().orEmpty().any { kept -> kept.token == entry.token }
                        },
                        digest = IosQobuzRequestDigest(),
                    ),
                )
            }
        }
        val pooled = pool?.qobuzAccounts().orEmpty().mapNotNull { account ->
            val feedId = account.id?.takeIf { it > 0L } ?: return@mapNotNull null
            val scope = accounts.admitPoolEntry(
                ContentProvider.QOBUZ, feedId, mapOf(
                    "token" to account.token,
                    "appId" to account.appId,
                    "appSecret" to account.appSecret,
                    "premium" to account.premium.toString(),
                ),
            ) ?: return@mapNotNull null
            Attempt(
                KtorQobuzRepository(
                    http,
                    nowMs = ::iosNowMillis,
                    access = QobuzAccess(scope, account.appId, account.token, account.appSecret, CredentialScope.PLAYBACK_POOL) { pool?.enabled() == true },
                    digest = IosQobuzRequestDigest(),
                ),
                poolId = feedId,
                pooled = true,
            )
        }
        val instanceBases = LinkedHashSet<String>().apply {
            addAll(snapshot?.qobuzInstanceList().orEmpty())
            addAll(pool?.qobuzInstances().orEmpty())
        }
        val instances = instanceBases.map { base ->
            Attempt(KtorQobuzRepository(http, nowMs = ::iosNowMillis, access = null, digest = null, instanceBase = base))
        }
        var failure: Exception? = null
        for (attempt in personal + pooled + instances) {
            try {
                attempt.repository.resolve(request)?.let { resolved ->
                    if (attempt.pooled) pool?.noteSuccess(IosPlaybackPool.QOBUZ, attempt.poolId)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                if (!attempt.pooled) throw auth
                pool?.report(IosPlaybackPool.QOBUZ, attempt.poolId, "dead")
                pool?.noteFailure(IosPlaybackPool.QOBUZ, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    if ((error as? ProviderHttpFailure)?.status == 401) pool?.report(IosPlaybackPool.QOBUZ, attempt.poolId, "dead")
                    pool?.noteFailure(IosPlaybackPool.QOBUZ, attempt.poolId)
                }
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }
}

/** The shared Apple Music developer bearer helper: the user's own bearer while usable, otherwise the scraped one. */
private class IosAppleDevToken(http: HttpClient, preferences: () -> PreferenceSnapshot?) {
    private val inner = PaxsenixAppleMusicToken(http, { preferences()?.get(AppleMusicDevTokenKey) })

    suspend fun bearer(): String? = inner.ensureFresh()
}

/**
 * Fetches `/v1/me/storefront` for one media-user-token, as the original does, and caches each answer for a day so a
 * resolve does not cost a request per attempt. An unreachable storefront falls back to "us".
 */
private class IosAppleStorefront(private val http: HttpClient, private val nowMs: () -> Long = ::iosNowMillis) {
    private val cache = HashMap<String, Pair<String, Long>>()

    suspend fun forToken(devToken: String, mediaUserToken: String): String {
        val cached = cache[mediaUserToken]
        val now = nowMs()
        if (cached != null && now - cached.second < STOREFRONT_TTL_MS) return cached.first
        val storefront = try {
            val response = http.get("https://amp-api.music.apple.com/v1/me/storefront") {
                header("Authorization", "Bearer $devToken")
                header("Media-User-Token", mediaUserToken)
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
            }
            if (response.status.value != 200) null else {
                val data = (kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]
                    as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                ((data?.get("attributes") as? JsonObject)?.get("defaultLanguageTag") as? JsonPrimitive)?.contentOrNull
                    ?.substringBefore('-')?.takeIf { it.isNotBlank() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: cached?.first ?: "us"
        cache[mediaUserToken] = storefront to now
        return storefront
    }

    companion object {
        private const val STOREFRONT_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Apple Music over the user's media-user-token (with the shared developer bearer) and the Playback Pool tokens.
 * Neither the developer bearer nor a media-user-token ships in the build: without a user token and a pool the source
 * answers no match. A dead pooled token is reported and the next is tried.
 */
private class IosAppleMusicSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val pool: IosPlaybackPool?,
) : AudioProviderRepository {
    override val source = AudioSourceType.APPLE
    private val devToken = IosAppleDevToken(http, preferences)
    private val storefront = IosAppleStorefront(http)

    private class Attempt(val repository: KtorAppleMusicRepository, val poolId: Long? = null, val pooled: Boolean = false)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val dev = devToken.bearer() ?: return null
        val snapshot = preferences()
        val attempts = ArrayList<Attempt>()
        val mediaUser = snapshot?.get(AppleMusicMediaUserTokenKey)?.takeIf { it.isNotBlank() }
        if (mediaUser != null) {
            val scope = providerAccountScope(ContentProvider.APPLE.id, CredentialScope.PERSONAL)
            if (scope != null) {
                attempts += Attempt(
                    KtorAppleMusicRepository(
                        http,
                        AppleMusicAccess(scope, dev, mediaUser, storefront.forToken(dev, mediaUser), CredentialScope.PERSONAL) {
                            preferences()?.get(AppleMusicMediaUserTokenKey) == mediaUser
                        },
                    ),
                )
            }
        }
        for (account in pool?.appleMusicAccounts().orEmpty()) {
            val feedId = account.id?.takeIf { it > 0L } ?: continue
            val scope = accounts.admitPoolEntry(
                ContentProvider.APPLE, feedId, mapOf("token" to account.mediaUserToken, "premium" to account.premium.toString()),
            ) ?: continue
            attempts += Attempt(
                KtorAppleMusicRepository(
                    http,
                    AppleMusicAccess(scope, dev, account.mediaUserToken, storefront.forToken(dev, account.mediaUserToken),
                        CredentialScope.PLAYBACK_POOL) { pool?.enabled() == true },
                ),
                poolId = feedId,
                pooled = true,
            )
        }
        var failure: Exception? = null
        for (attempt in attempts) {
            try {
                attempt.repository.resolve(request)?.let { resolved ->
                    if (attempt.pooled) pool?.noteSuccess(IosPlaybackPool.APPLE, attempt.poolId)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                if (!attempt.pooled) throw auth
                pool?.report(IosPlaybackPool.APPLE, attempt.poolId, "dead")
                pool?.noteFailure(IosPlaybackPool.APPLE, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    if ((error as? ProviderHttpFailure)?.status == 401) pool?.report(IosPlaybackPool.APPLE, attempt.poolId, "dead")
                    pool?.noteFailure(IosPlaybackPool.APPLE, attempt.poolId)
                }
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }
}

/**
 * Amazon Music over the user's instance list and bearer material (the operator bypass token or a live Turnstile JWT),
 * then the Playback Pool instance tier. Pool entries without a base URL are the account tier this host cannot use, so
 * they are never attempted. An expired Turnstile JWT is dropped, as on Android.
 */
private class IosAmazonSource(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val accounts: VaultProviderAccountStore,
    private val pool: IosPlaybackPool?,
) : AudioProviderRepository {
    override val source = AudioSourceType.AMAZON

    private class Attempt(val repository: KtorAmazonRepository, val poolId: Long? = null, val pooled: Boolean = false)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences() ?: return null
        val attempts = ArrayList<Attempt>()
        val bypass = snapshot[AmazonBypassTokenKey]?.takeIf { it.isNotBlank() }
        val jwtExpiry = snapshot[AmazonTurnstileJwtExpiryMsKey]
        val turnstile = snapshot[AmazonTurnstileJwtKey]?.takeIf { it.isNotBlank() && (jwtExpiry ?: 0L) > iosNowMillis() }
        if (bypass != null || turnstile != null) {
            val scope = providerAccountScope(ContentProvider.AMAZON.id, CredentialScope.PERSONAL)
            if (scope != null) {
                snapshot.amazonInstanceList().forEach { base ->
                    attempts += Attempt(
                        KtorAmazonRepository(http, base, AmazonInstanceAccess(scope, bypass, turnstile) {
                            preferences()?.let { it[AmazonBypassTokenKey] == bypass || it[AmazonTurnstileJwtKey] == turnstile } == true
                        }),
                    )
                }
            }
        }
        for (account in pool?.amazonInstances().orEmpty()) {
            val feedId = account.id?.takeIf { it > 0L } ?: continue
            val base = account.baseUrl ?: continue
            val operator = account.bypassToken
            val poolJwt = account.turnstileJwt.takeIf { (account.turnstileJwtExpiresAtMs ?: Long.MAX_VALUE) > iosNowMillis() }
            if (operator.isNullOrBlank() && poolJwt.isNullOrBlank()) continue
            val scope = accounts.admitPoolEntry(
                ContentProvider.AMAZON, feedId, buildMap {
                    put("baseUrl", base)
                    operator?.let { put("bypassToken", it) }
                    poolJwt?.let { put("turnstileJwt", it) }
                    account.turnstileJwtExpiresAtMs?.let { put("turnstileJwtExpiresAtMs", it.toString()) }
                    put("premium", account.premium.toString())
                },
            ) ?: continue
            attempts += Attempt(
                KtorAmazonRepository(http, base, AmazonInstanceAccess(scope, operator, poolJwt) { pool?.enabled() == true }),
                poolId = feedId,
                pooled = true,
            )
        }
        var failure: Exception? = null
        for (attempt in attempts) {
            try {
                attempt.repository.resolve(request)?.let { resolved ->
                    if (attempt.pooled) pool?.noteSuccess(IosPlaybackPool.AMAZON, attempt.poolId)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                if (!attempt.pooled) throw auth
                pool?.report(IosPlaybackPool.AMAZON, attempt.poolId, "dead")
                pool?.noteFailure(IosPlaybackPool.AMAZON, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    if ((error as? ProviderHttpFailure)?.status == 401) pool?.report(IosPlaybackPool.AMAZON, attempt.poolId, "dead")
                    pool?.noteFailure(IosPlaybackPool.AMAZON, attempt.poolId)
                }
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }
}

/**
 * JioSaavn over the shared repository and the iOS DES decoder. The decoder key is host-supplied material read from the
 * build settings ([IosProviderCredentials.jioSaavnDesKey]); the source is not composed without it, so it answers as
 * unavailable instead of shipping a copied key.
 */
internal fun iosJioSaavnSource(http: HttpClient): AudioProviderRepository? =
    IosProviderCredentials.jioSaavnDesKey?.let { KtorJioSaavnRepository(http, IosJioSaavnUrlDecoder(it)) }

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
