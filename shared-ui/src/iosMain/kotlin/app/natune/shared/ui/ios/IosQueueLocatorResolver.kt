package app.natune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import app.natune.shared.data.lyrics.PaxsenixAppleMusicToken
import app.natune.shared.data.platform.ios.AmazonPoolAccount
import app.natune.shared.data.platform.ios.AppleMusicPoolAccount
import app.natune.shared.data.platform.ios.DeezerPoolAccount
import app.natune.shared.data.platform.ios.IosPlaybackPool
import app.natune.shared.data.platform.ios.IosPlaybackPort
import app.natune.shared.data.platform.ios.IosProviderCredentials
import app.natune.shared.data.platform.ios.QobuzPoolAccount
import app.natune.shared.data.platform.ios.TidalPoolAccount
import app.natune.shared.data.platform.ios.iosNowMillis
import app.natune.shared.data.provider.AmazonInstanceAccess
import app.natune.shared.data.provider.AppleMusicAccess
import app.natune.shared.data.provider.AudioResolution
import app.natune.shared.data.provider.DeezerAccess
import app.natune.shared.data.provider.DeezerInstanceResolver
import app.natune.shared.data.provider.NoPoolDiscoveryStore
import app.natune.shared.data.provider.IosQobuzRequestDigest
import app.natune.shared.data.provider.KtorAmazonAuthorization
import app.natune.shared.data.provider.KtorAmazonRepository
import app.natune.shared.data.provider.KtorAppleMusicRepository
import app.natune.shared.data.provider.KtorDeezerRepository
import app.natune.shared.data.provider.KtorQobuzBackupRepository
import app.natune.shared.data.provider.KtorQobuzRepository
import app.natune.shared.data.provider.KtorQqMusicRepository
import app.natune.shared.data.provider.KtorTidalRepository
import app.natune.shared.data.provider.OrderedAudioResolver
import app.natune.shared.data.provider.PoolFailureKind
import app.natune.shared.data.provider.PoolRecoverySettledException
import app.natune.shared.data.provider.QobuzAccess
import app.natune.shared.data.provider.QobuzInstanceResolver
import app.natune.shared.data.provider.QqMusicAccess
import app.natune.shared.data.provider.TidalRateLimitGate
import app.natune.shared.data.provider.TidalAccess
import app.natune.shared.data.provider.TidalInstanceResolver
import app.natune.shared.data.provider.YouTubeResolutionPort
import app.natune.shared.data.provider.classifyPoolFailure
import app.natune.shared.data.provider.racePoolAttempts
import app.natune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import app.natune.shared.domain.platform.CredentialScope
import app.natune.shared.domain.platform.PlatformSecret
import app.natune.shared.domain.platform.ProviderAccountCommitResult
import app.natune.shared.domain.platform.ProviderIdentity
import app.natune.shared.domain.playback.PlaybackLocator
import app.natune.shared.domain.repository.AudioProviderRepository
import app.natune.shared.domain.repository.AudioResolveRequest
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.PreparedAudio
import app.natune.shared.domain.repository.QueueLocatorResolver
import app.natune.shared.domain.repository.QueueSourceRecovery
import app.natune.shared.domain.repository.TerminalPlaybackAuthentication
import app.natune.shared.domain.repository.toPlaybackLocator
import app.natune.shared.domain.settings.AmazonBypassTokenKey
import app.natune.shared.domain.settings.AmazonTurnstileJwtExpiryMsKey
import app.natune.shared.domain.settings.AmazonTurnstileJwtKey
import app.natune.shared.domain.settings.AppleMusicDevTokenKey
import app.natune.shared.domain.settings.AppleMusicMediaUserTokenKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.SongSourceOverrideKey
import app.natune.shared.domain.settings.TidalAccountQualityFallbackKey
import app.natune.shared.domain.settings.TidalAccountFirstKey
import app.natune.shared.domain.settings.amazonInstanceList
import app.natune.shared.domain.settings.deezerInstanceList
import app.natune.shared.domain.settings.qobuzInstanceList
import app.natune.shared.domain.settings.qobuzTokens
import app.natune.shared.domain.settings.tidalCountryCode
import app.natune.shared.domain.settings.tidalInstanceList
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.DirectStream
import app.natune.shared.domain.source.SongSourceOverride
import app.natune.shared.domain.source.YouTubeFallbackTier
import app.natune.shared.domain.source.toPlaybackLocator
import app.natune.shared.domain.telegram.TelegramTrackRef
import app.natune.shared.ui.host.TidalSessionRefresh
import app.natune.shared.ui.host.hostSourceSnapshot
import app.natune.shared.ui.host.toAudioResolveRequest
import app.natune.shared.ui.host.unresolvedSourceReason
import app.natune.shared.ui.host.withSourcePins
import app.natune.shared.ui.settings.VaultProviderAccountStore
import app.natune.shared.ui.settings.playbackPoolPending
import app.natune.shared.ui.settings.poolScopeForFeedId
import app.natune.shared.ui.settings.providerAccountScope
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
    private val localFile: (app.natune.shared.domain.repository.ContentRef) -> String? = { null },
    /** The stored file of a completed download, or null when the song is not a stored download. */
    private val downloadedFile: suspend (app.natune.shared.domain.repository.ContentRef) -> String? = { null },
    private val cachedFile: suspend (app.natune.shared.domain.repository.ContentRef, Set<AudioSourceType>) -> String? = { _, _ -> null },
) : QueueLocatorResolver {
    override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator = locate(occurrence, QueueSourceRecovery())

    override suspend fun recover(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator =
        locate(occurrence, recovery)

    private suspend fun locate(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator {
        if (occurrence.item.ref.provider == LOCAL_PROVIDER) {
            val path = localFile(occurrence.item.ref)
                ?: throw IllegalStateException("the imported file of this local song is gone")
            return PlaybackLocator.LocalFile(path)
        }
        // An explicit source choice must reach the ordered resolver, rather than playing another source's stored bytes.
        val explicitSource = SongSourceOverride.get(preferences()?.get(SongSourceOverrideKey), occurrence.item.ref.id)
        if (explicitSource == null) {
            downloadedFile(occurrence.item.ref)?.let { return PlaybackLocator.LocalFile(it) }
            cachedFile(occurrence.item.ref, recovery.rejectedSources)?.let { return PlaybackLocator.LocalFile(it) }
        }
        // A Telegram song is a TDLib file the playback port streams itself; no provider chain can answer for it.
        if (occurrence.item.ref.provider == TelegramTrackRef.SCHEME) {
            val track = TelegramTrackRef.fromMediaId(occurrence.item.ref.id)
                ?: throw IllegalStateException("invalid Telegram media id: ${occurrence.item.ref.id}")
            return PlaybackLocator.TelegramFile(track)
        }
        // The per-source ids the source chooser stored join the request. A "play from" pin forces the chain for this one
        // song, as the Android service and the desktop host do; a refused pinned source falls through to the ordinary chain.
        val request = occurrence.toAudioResolveRequest(preferences()).withSourcePins(preferences(), occurrence.item.ref.id)
            .copy(forceFreshStream = recovery.refreshSource != null)
        val pinned = SongSourceOverride.get(preferences()?.get(SongSourceOverrideKey), occurrence.item.ref.id)
        var refused = recovery.rejectedSources
        while (true) {
            val prepared = when (val resolution = audio.resolve(request, recovery.settings(hostSourceSnapshot(preferences(), spotifyStreamingReady = iosSpotifyStreamingReady(preferences()))),
                pinned = recovery.pinned(pinned), rejected = refused)) {
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
 * discovered instances to TIDAL, Qobuz, Deezer, Apple and Amazon. JioSaavn uses the build's DES key or its explicitly selected key-free web-player route. Spotify uses the portable Echo pipeline under its dedicated streaming account; other engines remain
 * unavailable until their platform transports can honour the selected method.
 */
internal fun iosAudioProviders(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    tidalSessionRefresh: TidalSessionRefresh?,
    pool: IosPlaybackPool?,
    modules: AudioProviderRepository? = null,
): List<AudioProviderRepository> = listOfNotNull(
    IosTidalSource(http, accounts, preferences, tidalSessionRefresh, pool),
    KtorQobuzBackupRepository(http),
    IosDeezerSource(http, accounts, pool, preferences),
    IosQobuzSource(http, accounts, preferences, pool),
    IosAppleMusicSource(http, accounts, preferences, pool),
    IosAmazonSource(http, preferences, accounts, pool),
    iosJioSaavnSource(http, preferences),
    IosQqMusicSource(http, accounts),
    IosSpotifySource(http, accounts, preferences),
    modules,
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
 * otherwise the instances first. The instances form one racing route for the session, so their health and cooldowns
 * persist across resolves, as the desktop host's do. The pooled accounts race through the shared [racePoolAttempts]
 * helper the way the Android resolver does — bounded lanes, the first answer wins and the losers are cancelled — while
 * the personal account and the instances stay in order. A terminal authentication failure of the personal account
 * stops the source; a pooled account that fails authentication or answers HTTP 401 is reported dead to the pool.
 */
private class IosTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val sessionRefresh: TidalSessionRefresh?,
    private val pool: IosPlaybackPool?,
) : AudioProviderRepository {
    private val accountRateLimit = TidalRateLimitGate(::iosNowMillis)
    override val source = AudioSourceType.TIDAL

    private class Attempt(val repository: KtorTidalRepository, val poolId: Long? = null, val pooled: Boolean = false,
        val stale: String = "")

    /** One pooled-account attempt; also how a re-leased credential is retried after recovery. */
    private suspend fun pooledAttempt(account: TidalPoolAccount): Attempt? {
        val feedId = account.id?.takeIf { it > 0L } ?: return null
        val scope = accounts.admitPoolEntry(
            ContentProvider.TIDAL, feedId, buildMap {
                put("token", account.token)
                account.refreshToken?.let { put("refreshToken", it) }
                account.countryCode?.let { put("countryCode", it) }
                put("premium", account.premium.toString())
            },
        ) ?: return null
        return Attempt(
            KtorTidalRepository(
                http,
                TidalAccess(scope = scope, bearer = account.token, credentialScope = CredentialScope.PLAYBACK_POOL, isActive = { pool?.enabled() == true }),
                countryCode = account.countryCode?.trim()?.takeIf { it.length == 2 } ?: "US",
                accountQualityFallback = { preferences()?.get(TidalAccountQualityFallbackKey) == true },
                accountRateLimit = accountRateLimit,
            ),
            poolId = feedId,
            pooled = true,
            stale = account.token,
        )
    }

    /**
     * Recovers a pooled TIDAL account that just failed: one re-lease (single-flight per account)
     * and one retry with the credential the pool now serves; the account is reported dead only
     * when it still fails for real. Answers the retry's stream, or null.
     */
    private suspend fun recoverPooled(
        attempt: Attempt,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? = pool?.recoverPooledAccount(
        IosPlaybackPool.TIDAL, attempt.poolId, attempt.stale, kind,
        current = { pool?.tidalSnapshot()?.firstOrNull { it.id == attempt.poolId }?.token },
        resolveFresh = {
            pool?.tidalSnapshot()?.firstOrNull { it.id == attempt.poolId }
                ?.let { pooledAttempt(it) }?.repository?.resolve(request)
        },
    )

    /** The session's one anonymous racing route. It is rebuilt only when its merged list, country or Pool switch changes. */
    private class AnonymousRoute(val instances: List<String>, val country: String, val poolOn: Boolean,
        val repository: KtorTidalRepository)
    private val routeLock = Mutex()
    private var anonymous: AnonymousRoute? = null

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences()
        val country = snapshot?.tidalCountryCode()?.takeIf { it.length == 2 } ?: "US"
        val personal = personalRepository(country)?.let { Attempt(it) }
        val pooled = pool?.tidalAccounts().orEmpty().mapNotNull { account -> pooledAttempt(account) }
        val instanceBases = LinkedHashSet<String>().apply {
            addAll(snapshot?.tidalInstanceList().orEmpty())
            addAll(pool?.tidalInstances().orEmpty())
        }.toList()
        // One attempt covers the whole list: the racing resolver keeps its health and cooldowns across it.
        val instances = if (instanceBases.isEmpty()) emptyList() else listOf(Attempt(anonymousRepository(instanceBases, country)))
        val accountFirst = snapshot?.get(TidalAccountFirstKey) ?: true
        val accountAttempts = listOfNotNull(personal) + pooled
        val attempts = if (accountFirst) accountAttempts + instances else instances + accountAttempts
        var failure: Exception? = null
        // Only un-pooled attempts reach this walker, so a terminal refusal still ends the source.
        suspend fun sequential(attempt: Attempt): PreparedAudio? = try {
            attempt.repository.resolve(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (auth: TerminalPlaybackAuthentication) {
            throw auth
        } catch (error: Exception) {
            failure = error
            null
        }
        val firstPooled = attempts.indexOfFirst { it.pooled }.let { if (it < 0) attempts.size else it }
        val raced = attempts.drop(firstPooled).takeWhile { it.pooled }
        for (attempt in attempts.take(firstPooled)) sequential(attempt)?.let { return it }
        if (raced.isNotEmpty()) {
            val outcome = racePoolAttempts(
                attempts = raced,
                resolve = { attempt ->
                    // A raced lane earns the same re-lease and one retry the sequential walk gave a pooled account.
                    try {
                        attempt.repository.resolve(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (auth: TerminalPlaybackAuthentication) {
                        recoverPooled(attempt, PoolFailureKind.AUTH_REJECTED, request)
                            ?: throw PoolRecoverySettledException(auth)
                    } catch (error: Exception) {
                        recoverPooled(attempt, classifyPoolFailure(error), request)
                            ?: throw PoolRecoverySettledException(error)
                    }
                },
                report = { attempt, type ->
                    pool?.let { owner ->
                        owner.deadReports.report(IosPlaybackPool.TIDAL, attempt.poolId, type) {
                            owner.report(IosPlaybackPool.TIDAL, attempt.poolId, type)
                        }
                    }
                },
                noteSuccess = { pool?.noteSuccess(IosPlaybackPool.TIDAL, it.poolId) },
                noteFailure = { pool?.noteFailure(IosPlaybackPool.TIDAL, it.poolId) },
            )
            outcome.winner?.let { return it }
            outcome.failure?.let { failure = it }
        }
        for (attempt in attempts.drop(firstPooled + raced.size)) sequential(attempt)?.let { return it }
        failure?.let { throw it }
        return null
    }

    /**
     * The anonymous repository for this list. Its racing resolver is created once and reused, so a resolve that follows
     * another still knows which instances failed, which are cooling down and which answered 429. A changed list, country
     * or Pool switch replaces it. It carries no account, so no personal or pooled state reaches it.
     */
    private suspend fun anonymousRepository(instances: List<String>, country: String): KtorTidalRepository = routeLock.withLock {
        val poolOn = pool?.enabled() == true
        anonymous?.takeIf { it.instances == instances && it.country == country && it.poolOn == poolOn }?.repository
            ?: KtorTidalRepository(
                http,
                TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE),
                countryCode = country,
                resolver = TidalInstanceResolver(http, nowMs = ::iosNowMillis),
                instances = { instances },
            ).also { anonymous = AnonymousRoute(instances, country, poolOn, it) }
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
            accountQualityFallback = { preferences()?.get(TidalAccountQualityFallbackKey) == true },
            accountRateLimit = accountRateLimit,
        )
    }
}

/**
 * Deezer over the personal ARL lease, then the Playback Pool ARLs, then the user's own instances and the pool's discovered
 * ones. Each stage that answers no match or fails for a transient reason hands over to the next; a terminal
 * authentication failure of the personal account still stops the source. One repository is kept per lease generation,
 * so its gateway session is reused until the account changes. The instance stage keeps one resolver for the session and
 * reads the current instance list and Pool switch on every resolve.
 */
private class IosDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val pool: IosPlaybackPool?,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.DEEZER
    private var cached: Pair<Long, KtorDeezerRepository>? = null
    private val pooledRepositories = HashMap<String, KtorDeezerRepository>()
    private val instanceResolver = DeezerInstanceResolver(
        http,
        ::iosNowMillis,
        poolEnabled = { pool?.enabled() == true },
        sourceProviderUrl = { pool?.discoveryBaseUrl().orEmpty() },
        sourceProviderKey = { pool?.discoveryReadKey().orEmpty() },
        discoveryStore = pool?.discoveryStore(IosPlaybackPool.DEEZER) ?: NoPoolDiscoveryStore,
    )
    private val instanceRepository = KtorDeezerRepository(
        http,
        nowMs = ::iosNowMillis,
        resolver = instanceResolver,
        instances = { preferences()?.deezerInstanceList().orEmpty() },
    )

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        var failure: Exception? = null
        val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalDeezerLease(it) }
        if (lease != null) {
            try {
                val repository = cached?.takeIf { it.first == lease.generation }?.second ?: KtorDeezerRepository(
                    http,
                    nowMs = ::iosNowMillis,
                    access = DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted),
                ).also { cached = lease.generation to it }
                repository.resolve(request)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                throw auth
            } catch (error: Exception) {
                // A transient personal failure must not end Deezer while the pool and the instances can still answer.
                failure = error
            }
        }
        try {
            resolvePooled(request)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        try {
            instanceRepository.resolve(request)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        failure?.let { throw it }
        return null
    }

    /** One repository per pooled ARL, so its gateway session is reused until the credential changes. */
    private suspend fun pooledRepository(owner: IosPlaybackPool, account: DeezerPoolAccount): KtorDeezerRepository =
        pooledRepositories.getOrPut(account.arl) {
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

    /**
     * Recovers a pooled Deezer account that just failed: one re-lease (the pool rotates ARLs
     * server-side) and one retry with the credential it now serves; the account is reported dead
     * only when it still fails for real. Answers the retry's stream, or null.
     */
    private suspend fun recoverPooled(
        owner: IosPlaybackPool,
        account: DeezerPoolAccount,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? = owner.recoverPooledAccount(
        IosPlaybackPool.DEEZER, account.id, account.arl, kind,
        current = { owner.deezerSnapshot().firstOrNull { it.id == account.id }?.arl },
        resolveFresh = {
            owner.deezerSnapshot().firstOrNull { it.id == account.id }
                ?.let { pooledRepository(owner, it) }?.resolve(request)
        },
    )

    private suspend fun resolvePooled(request: AudioResolveRequest): PreparedAudio? {
        val owner = pool ?: return null
        var failure: Exception? = null
        for (account in owner.deezerAccounts()) {
            val repository = pooledRepository(owner, account)
            try {
                repository.resolve(request)?.let { resolved ->
                    owner.noteSuccess(IosPlaybackPool.DEEZER, account.id)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                recoverPooled(owner, account, PoolFailureKind.AUTH_REJECTED, request)?.let { return it }
                owner.noteFailure(IosPlaybackPool.DEEZER, account.id)
            } catch (error: Exception) {
                recoverPooled(owner, account, classifyPoolFailure(error), request)?.let { return it }
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

    private class Attempt(val repository: KtorQobuzRepository, val poolId: Long? = null, val pooled: Boolean = false,
        val stale: Triple<String, String, String> = Triple("", "", ""))

    /** One pooled-account attempt; also how a re-leased credential is retried after recovery. */
    private suspend fun pooledAttempt(account: QobuzPoolAccount): Attempt? {
        val feedId = account.id?.takeIf { it > 0L } ?: return null
        val scope = accounts.admitPoolEntry(
            ContentProvider.QOBUZ, feedId, mapOf(
                "token" to account.token,
                "appId" to account.appId,
                "appSecret" to account.appSecret,
                "premium" to account.premium.toString(),
            ),
        ) ?: return null
        return Attempt(
            KtorQobuzRepository(
                http,
                nowMs = ::iosNowMillis,
                access = QobuzAccess(scope, account.appId, account.token, account.appSecret, CredentialScope.PLAYBACK_POOL) { pool?.enabled() == true },
                digest = IosQobuzRequestDigest(),
            ),
            poolId = feedId,
            pooled = true,
            stale = Triple(account.token, account.appId, account.appSecret),
        )
    }

    /**
     * Recovers a pooled Qobuz account that just failed: one re-lease (the pool can rotate
     * tokens, appIds and appSecrets server-side) and one retry with the credential it now
     * serves; the account is reported dead only when it still fails for real.
     */
    private suspend fun recoverPooled(
        attempt: Attempt,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? = pool?.recoverPooledAccount(
        IosPlaybackPool.QOBUZ, attempt.poolId, attempt.stale, kind,
        current = {
            pool?.qobuzSnapshot()?.firstOrNull { it.id == attempt.poolId }
                ?.let { Triple(it.token, it.appId, it.appSecret) }
        },
        resolveFresh = {
            pool?.qobuzSnapshot()?.firstOrNull { it.id == attempt.poolId }
                ?.let { pooledAttempt(it) }?.repository?.resolve(request)
        },
    )

    /**
     * One resolver for the session, so its cooldowns, probe results and discovered instances persist across resolves. The
     * Pool switch gates its discovery, as the desktop host's does.
     */
    private val instanceResolver = QobuzInstanceResolver(
        http,
        ::iosNowMillis,
        poolEnabled = { pool?.enabled() == true },
        sourceProviderUrl = { pool?.discoveryBaseUrl().orEmpty() },
        sourceProviderKey = { pool?.discoveryReadKey().orEmpty() },
        discoveryStore = pool?.discoveryStore(IosPlaybackPool.QOBUZ) ?: NoPoolDiscoveryStore,
    )

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
        val pooled = pool?.qobuzAccounts().orEmpty().mapNotNull { account -> pooledAttempt(account) }
        // The user's list is the resolver's manual list. Pool-discovered instances join it inside the resolver while the Pool
        // switch is on, so a removed base never lingers and discovery runs once per cache window, not once per resolve.
        instanceResolver.setInstances(snapshot?.qobuzInstanceList().orEmpty())
        val instances = if (instanceResolver.hasInstances()) listOf(Attempt(KtorQobuzRepository(http, nowMs = ::iosNowMillis,
            resolver = instanceResolver, instances = { preferences()?.qobuzInstanceList().orEmpty() }))) else emptyList()
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
                recoverPooled(attempt, PoolFailureKind.AUTH_REJECTED, request)?.let { return it }
                pool?.noteFailure(IosPlaybackPool.QOBUZ, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    recoverPooled(attempt, classifyPoolFailure(error), request)?.let { return it }
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
internal class IosAppleDevToken(http: HttpClient, preferences: () -> PreferenceSnapshot?) {
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
 * The Apple Music catalogue over the same account admission the playback source uses: the personal
 * media-user-token when present, otherwise the first pooled entry. Search needs no second account owner, and no
 * developer bearer is bundled. Null means this host has no usable Apple Music account.
 */
internal suspend fun iosAppleMusicSearchRepository(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    pool: IosPlaybackPool?,
): KtorAppleMusicRepository? {
    val dev = IosAppleDevToken(http, preferences).bearer() ?: return null
    val mediaUser = preferences()?.get(AppleMusicMediaUserTokenKey)?.takeIf { it.isNotBlank() }
    if (mediaUser != null) {
        val scope = providerAccountScope(ContentProvider.APPLE.id, CredentialScope.PERSONAL) ?: return null
        return KtorAppleMusicRepository(
            http,
            AppleMusicAccess(scope, dev, mediaUser, null, CredentialScope.PERSONAL) {
                preferences()?.get(AppleMusicMediaUserTokenKey) == mediaUser
            },
        )
    }
    val pooled = pool?.appleMusicAccounts()?.firstOrNull { it.id != null && it.mediaUserToken.isNotBlank() } ?: return null
    val scope = accounts.admitPoolEntry(
        ContentProvider.APPLE,
        requireNotNull(pooled.id),
        mapOf("token" to pooled.mediaUserToken, "premium" to pooled.premium.toString()),
    ) ?: return null
    return KtorAppleMusicRepository(
        http,
        AppleMusicAccess(scope, dev, pooled.mediaUserToken, null, CredentialScope.PLAYBACK_POOL) {
            pool?.enabled() == true
        },
    )
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

    private class Attempt(val repository: KtorAppleMusicRepository, val poolId: Long? = null, val pooled: Boolean = false,
        val stale: String = "")

    /** One pooled-account attempt; also how a re-leased credential is retried after recovery. */
    private suspend fun pooledAttempt(dev: String, account: AppleMusicPoolAccount): Attempt? {
        val feedId = account.id?.takeIf { it > 0L } ?: return null
        val scope = accounts.admitPoolEntry(
            ContentProvider.APPLE, feedId, mapOf("token" to account.mediaUserToken, "premium" to account.premium.toString()),
        ) ?: return null
        return Attempt(
            KtorAppleMusicRepository(
                http,
                AppleMusicAccess(scope, dev, account.mediaUserToken, storefront.forToken(dev, account.mediaUserToken),
                    CredentialScope.PLAYBACK_POOL) { pool?.enabled() == true },
                propagateAuthErrors = true,
            ),
            poolId = feedId,
            pooled = true,
            stale = account.mediaUserToken,
        )
    }

    /**
     * Recovers a pooled Apple Music account that just failed: one re-lease (the pool can serve a
     * replacement media-user-token) and one retry with the credential it now serves; the account
     * is reported dead only when it still fails for real.
     */
    private suspend fun recoverPooled(
        attempt: Attempt,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? = pool?.recoverPooledAccount(
        IosPlaybackPool.APPLE, attempt.poolId, attempt.stale, kind,
        current = { pool?.appleMusicSnapshot()?.firstOrNull { it.id == attempt.poolId }?.mediaUserToken },
        resolveFresh = {
            val dev = devToken.bearer()
            if (dev == null) {
                null
            } else {
                pool?.appleMusicSnapshot()?.firstOrNull { it.id == attempt.poolId }
                    ?.let { pooledAttempt(dev, it) }?.repository?.resolve(request)
            }
        },
    )

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
            pooledAttempt(dev, account)?.let { attempts += it }
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
                recoverPooled(attempt, PoolFailureKind.AUTH_REJECTED, request)?.let { return it }
                pool?.noteFailure(IosPlaybackPool.APPLE, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    recoverPooled(attempt, classifyPoolFailure(error), request)?.let { return it }
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

    private class Attempt(val repository: KtorAmazonRepository, val poolId: Long? = null, val pooled: Boolean = false,
        val stale: String = "")

    private fun marker(account: AmazonPoolAccount) = listOf(account.bypassToken, account.turnstileJwt).joinToString("|")

    private fun turnstileOrigin(): String = iosAmazonTurnstileOrigin() ?: KtorAmazonAuthorization.TURNSTILE_ORIGIN

    /** One pooled-instance attempt; also how a re-leased credential is retried after recovery. */
    private suspend fun pooledAttempt(account: AmazonPoolAccount, turnstileOrigin: String): Attempt? {
        val feedId = account.id?.takeIf { it > 0L } ?: return null
        val base = account.baseUrl ?: return null
        val operator = account.bypassToken
        val poolJwt = account.turnstileJwt.takeIf { (account.turnstileJwtExpiresAtMs ?: Long.MAX_VALUE) > iosNowMillis() }
        if (operator.isNullOrBlank() && poolJwt.isNullOrBlank()) return null
        val scope = accounts.admitPoolEntry(
            ContentProvider.AMAZON, feedId, buildMap {
                put("baseUrl", base)
                operator?.let { put("bypassToken", it) }
                poolJwt?.let { put("turnstileJwt", it) }
                account.turnstileJwtExpiresAtMs?.let { put("turnstileJwtExpiresAtMs", it.toString()) }
                put("premium", account.premium.toString())
            },
        ) ?: return null
        return Attempt(
            KtorAmazonRepository(http, base, AmazonInstanceAccess(scope, operator, poolJwt) { pool?.enabled() == true }, turnstileOrigin = turnstileOrigin),
            poolId = feedId,
            pooled = true,
            stale = marker(account),
        )
    }

    /**
     * Recovers a pooled Amazon entry that just failed: one re-lease (the pool rotates operator
     * and Turnstile tokens server-side) and one retry with the credentials it now serves; the
     * entry is reported dead only when it still fails for real.
     */
    private suspend fun recoverPooled(
        attempt: Attempt,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? = pool?.recoverPooledAccount(
        IosPlaybackPool.AMAZON, attempt.poolId, attempt.stale, kind,
        current = { pool?.amazonSnapshot()?.firstOrNull { it.id == attempt.poolId }?.let { marker(it) } },
        resolveFresh = {
            pool?.amazonSnapshot()?.firstOrNull { it.id == attempt.poolId }
                ?.let { pooledAttempt(it, turnstileOrigin()) }?.repository?.resolve(request)
        },
    )

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences() ?: return null
        val attempts = ArrayList<Attempt>()
        val bypass = snapshot[AmazonBypassTokenKey]?.takeIf { it.isNotBlank() }
        val jwtExpiry = snapshot[AmazonTurnstileJwtExpiryMsKey]
        val turnstile = snapshot[AmazonTurnstileJwtKey]?.takeIf { it.isNotBlank() && (jwtExpiry ?: 0L) > iosNowMillis() }
        // The Origin the sitekey is locked to: the build's validated sign-in origin, or the shared
        // default when the build names none — the same value the authorization sheet exchanges with.
        val turnstileOrigin = iosAmazonTurnstileOrigin() ?: KtorAmazonAuthorization.TURNSTILE_ORIGIN
        if (bypass != null || turnstile != null) {
            val scope = providerAccountScope(ContentProvider.AMAZON.id, CredentialScope.PERSONAL)
            if (scope != null) {
                snapshot.amazonInstanceList().forEach { base ->
                    attempts += Attempt(
                        KtorAmazonRepository(http, base, AmazonInstanceAccess(scope, bypass, turnstile) {
                            preferences()?.let { it[AmazonBypassTokenKey] == bypass || it[AmazonTurnstileJwtKey] == turnstile } == true
                        }, turnstileOrigin = turnstileOrigin),
                    )
                }
            }
        }
        for (account in pool?.amazonInstances().orEmpty()) {
            pooledAttempt(account, turnstileOrigin)?.let { attempts += it }
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
                recoverPooled(attempt, PoolFailureKind.AUTH_REJECTED, request)?.let { return it }
                pool?.noteFailure(IosPlaybackPool.AMAZON, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    recoverPooled(attempt, classifyPoolFailure(error), request)?.let { return it }
                    pool?.noteFailure(IosPlaybackPool.AMAZON, attempt.poolId)
                }
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
