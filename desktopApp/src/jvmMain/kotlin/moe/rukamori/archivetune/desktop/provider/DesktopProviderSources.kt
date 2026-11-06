package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import java.util.UUID
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.desktop.DesktopCredentials
import moe.rukamori.archivetune.shared.data.provider.NoPoolDiscoveryStore
import moe.rukamori.archivetune.shared.data.provider.DeezerAccess
import moe.rukamori.archivetune.shared.data.provider.DeezerInstanceResolver
import moe.rukamori.archivetune.shared.data.provider.JvmJioSaavnUrlDecoder
import moe.rukamori.archivetune.shared.data.provider.KtorDeezerRepository
import moe.rukamori.archivetune.shared.data.provider.KtorJioSaavnRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorTidalRepository
import moe.rukamori.archivetune.shared.data.provider.PoolFailureKind
import moe.rukamori.archivetune.shared.data.provider.PoolRecoverySettledException
import moe.rukamori.archivetune.shared.data.provider.PoolRetryOutcome
import moe.rukamori.archivetune.shared.data.provider.QqMusicAccess
import moe.rukamori.archivetune.shared.data.provider.TidalAccess
import moe.rukamori.archivetune.shared.data.provider.TidalInstanceResolver
import moe.rukamori.archivetune.shared.data.provider.classifyPoolFailure
import moe.rukamori.archivetune.shared.data.provider.racePoolAttempts
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.TidalAccountFirstKey
import moe.rukamori.archivetune.shared.domain.settings.deezerInstanceList
import moe.rukamori.archivetune.shared.domain.settings.tidalCountryCode
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

/**
 * TIDAL over the personal account lease, the Playback Pool accounts and the HiFi instances (the user's own, then the
 * pool's discovered ones), in the shipped order: the accounts first while "account first" is on (the default), otherwise
 * the instances first. The personal account and the anonymous instances still run in that order; the pooled accounts
 * race through the shared [racePoolAttempts] helper the way the Android resolver does — bounded lanes, the first
 * answer wins and the losers are cancelled. A failed attempt moves to the next one. A terminal authentication failure
 * of the personal account stops the source; a pooled account that is refused is reported dead and the next one is
 * tried. The app ships no TIDAL
 * catalog token: instances search anonymously, and a direct TIDAL id needs an account. With no account, no pool and
 * no instance the source answers no match, and the TIDAL settings page asks the user to sign in.
 */
internal class DesktopTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val sessionRefresh: TidalSessionRefresh? = null,
    private val pool: DesktopPlaybackPool? = null,
) : AudioProviderRepository {
    override val source = AudioSourceType.TIDAL

    private class Attempt(val repository: KtorTidalRepository, val poolId: Long? = null, val pooled: Boolean = false,
        val credential: String = "")

    /** One pooled-account repository; also how a re-leased credential is retried after recovery. */
    private fun tidalRepository(account: TidalPoolAccount): KtorTidalRepository =
        KtorTidalRepository(
            http,
            TidalAccess(
                scope = "PLAYBACK_POOL:tidal:${account.id ?: 0L}",
                bearer = account.token,
                credentialScope = CredentialScope.PLAYBACK_POOL,
                isActive = { pool?.enabled() == true },
            ),
            countryCode = account.countryCode?.trim()?.takeIf { it.length == 2 } ?: "US",
        )

    /**
     * Recovers a pooled TIDAL account that just failed: one re-lease (single-flight per account)
     * and one retry with the credential the server now serves; the account is reported dead only
     * when it still fails for real. Answers the retry's stream, or null.
     */
    private suspend fun recoverPooled(
        attempt: Attempt,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? {
        val owner = pool ?: return null
        val poolId = attempt.poolId ?: return null
        return owner.deadReports.recoverAndReport(
            DesktopPlaybackPool.TIDAL, poolId, kind,
            stale = attempt.credential,
            current = { owner.tidalSnapshot().firstOrNull { it.id == poolId }?.token },
            recover = { owner.refresh(force = true) },
            retry = {
                val account = owner.tidalSnapshot().firstOrNull { it.id == poolId }
                    ?: return@recoverAndReport PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                try {
                    tidalRepository(account).resolve(request)
                        ?.let { PoolRetryOutcome.Success(it) }
                        ?: PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    PoolRetryOutcome.Failure(classifyPoolFailure(e))
                }
            },
            report = { type -> owner.report(DesktopPlaybackPool.TIDAL, poolId, type) },
        )
    }

    /** The session's one anonymous racing route. It is rebuilt only when its merged list, country or Pool switch changes. */
    private class AnonymousRoute(val instances: List<String>, val country: String, val poolOn: Boolean,
        val repository: KtorTidalRepository)
    private var anonymous: AnonymousRoute? = null

    /**
     * The attempts in shipped order. A resolve gets one anonymous attempt over the whole merged instance list, because the
     * racing resolver keeps its health, cooldown and rate-limit state across that list. A search has no racer, so each
     * instance answers its own `/search` in turn.
     */
    private suspend fun attempts(snapshot: PreferenceSnapshot?, search: Boolean): List<Attempt> {
        val country = snapshot?.tidalCountryCode()?.takeIf { it.length == 2 } ?: "US"
        val personal = personalRepository(country)?.let { Attempt(it) }
        val pooled = pool?.tidalAccounts().orEmpty().map { account ->
            Attempt(tidalRepository(account), poolId = account.id, pooled = true, credential = account.token)
        }
        val bases = LinkedHashSet<String>().apply {
            addAll(snapshot?.tidalInstanceList().orEmpty())
            addAll(pool?.tidalInstances().orEmpty())
        }.toList()
        val instances: List<Attempt> = when {
            search -> bases.map { base ->
                Attempt(KtorTidalRepository(http, TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE), instanceBase = base,
                    countryCode = country))
            }
            bases.isEmpty() -> emptyList()
            else -> listOf(Attempt(anonymousRepository(bases, country)))
        }
        val accountFirst = snapshot?.get(TidalAccountFirstKey) ?: true
        val accountAttempts = listOfNotNull(personal) + pooled
        return if (accountFirst) accountAttempts + instances else instances + accountAttempts
    }

    /**
     * The anonymous repository for this list. Its resolver is created once and reused, so a resolve that follows another
     * still knows which instances failed, which are cooling down and which answered 429. A changed list, country or Pool
     * switch replaces it.
     */
    private fun anonymousRepository(instances: List<String>, country: String): KtorTidalRepository = synchronized(this) {
        val poolOn = pool?.enabled() == true
        anonymous?.takeIf { it.instances == instances && it.country == country && it.poolOn == poolOn }?.repository
            ?: KtorTidalRepository(
                http,
                TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE),
                countryCode = country,
                resolver = TidalInstanceResolver(http),
                instances = { instances },
            ).also { anonymous = AnonymousRoute(instances, country, poolOn, it) }
    }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
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
        val ordered = attempts(preferences(), search = false)
        val firstPooled = ordered.indexOfFirst { it.pooled }.let { if (it < 0) ordered.size else it }
        val pooled = ordered.drop(firstPooled).takeWhile { it.pooled }
        for (attempt in ordered.take(firstPooled)) sequential(attempt)?.let { return it }
        if (pooled.isNotEmpty()) {
            val outcome = racePoolAttempts(
                attempts = pooled,
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
                        owner.deadReports.report(DesktopPlaybackPool.TIDAL, attempt.poolId, type) {
                            owner.report(DesktopPlaybackPool.TIDAL, attempt.poolId, type)
                        }
                    }
                },
                noteSuccess = { pool?.noteSuccess(DesktopPlaybackPool.TIDAL, it.poolId) },
                noteFailure = { pool?.noteFailure(DesktopPlaybackPool.TIDAL, it.poolId) },
            )
            outcome.winner?.let { return it }
            outcome.failure?.let { failure = it }
        }
        for (attempt in ordered.drop(firstPooled + pooled.size)) sequential(attempt)?.let { return it }
        failure?.let { throw it }
        return null
    }

    suspend fun search(query: String): List<ContentItem> {
        val request = ContentRequest(ContentProvider.TIDAL, ContentSurface.SEARCH,
            text = query, filter = ContentSearchFilter.SONGS)
        for (attempt in attempts(preferences(), search = true)) {
            try {
                val rows = attempt.repository.page(request, null).items.map { it.item }
                if (rows.isNotEmpty()) return rows
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Search failure is not a playback failure and must not report a pool account dead.
            }
        }
        return emptyList()
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
 * Deezer over the personal ARL lease, then the Playback Pool ARLs, then the user's own instances and the pool's discovered
 * ones. Each stage that answers no match or fails for a transient reason hands over to the next. A terminal authentication
 * failure of the personal account still stops the source. One repository is kept per lease generation, so its gateway
 * session is reused until the account changes; pooled ARLs get one repository per ARL for the same reason. The instance
 * stage keeps one resolver for the session and reads the current instance list and Pool switch on every resolve.
 */
internal class DesktopDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val pool: DesktopPlaybackPool? = null,
    private val preferences: () -> PreferenceSnapshot? = { null },
) : AudioProviderRepository {
    override val source = AudioSourceType.DEEZER
    private var cached: Pair<Long, KtorDeezerRepository>? = null
    private val pooledRepositories = HashMap<String, KtorDeezerRepository>()
    private val instanceResolver = DeezerInstanceResolver(
        http,
        System::currentTimeMillis,
        poolEnabled = { pool?.enabled() == true },
        sourceProviderUrl = { pool?.discoveryBaseUrl().orEmpty() },
        sourceProviderKey = { pool?.discoveryReadKey().orEmpty() },
        discoveryStore = pool?.discoveryStore(DesktopPlaybackPool.DEEZER) ?: NoPoolDiscoveryStore,
    )
    private val instanceRepository = KtorDeezerRepository(
        http,
        nowMs = System::currentTimeMillis,
        resolver = instanceResolver,
        instances = { preferences()?.deezerInstanceList().orEmpty() },
    )

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        var failure: Exception? = null
        val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalDeezerLease(it) }
        if (lease != null) {
            try {
                val repository = synchronized(this) {
                    cached?.takeIf { it.first == lease.generation }?.second ?: KtorDeezerRepository(
                        http,
                        nowMs = System::currentTimeMillis,
                        access = DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted),
                    ).also { cached = lease.generation to it }
                }
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
    private fun pooledRepository(owner: DesktopPlaybackPool, account: DeezerPoolAccount): KtorDeezerRepository =
        synchronized(this) {
            pooledRepositories.getOrPut(account.arl) {
                KtorDeezerRepository(
                    http,
                    nowMs = System::currentTimeMillis,
                    access = DeezerAccess(
                        "PLAYBACK_POOL:deezer:${account.id ?: 0L}",
                        account.arl,
                        CredentialScope.PLAYBACK_POOL,
                        isActive = { owner.enabled() },
                    ),
                )
            }
        }

    /**
     * Recovers a pooled Deezer account that just failed: one re-lease (the pool rotates ARLs
     * server-side) and one retry with the credential it now serves; the account is reported dead
     * only when it still fails for real. Answers the retry's stream, or null.
     */
    private suspend fun recoverPooled(
        owner: DesktopPlaybackPool,
        account: DeezerPoolAccount,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? {
        val poolId = account.id ?: return null
        return owner.deadReports.recoverAndReport(
            DesktopPlaybackPool.DEEZER, poolId, kind,
            stale = account.arl,
            current = { owner.deezerAccounts().firstOrNull { it.id == poolId }?.arl },
            recover = { owner.refresh(force = true) },
            retry = {
                val fresh = owner.deezerSnapshot().firstOrNull { it.id == poolId }
                    ?: return@recoverAndReport PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                try {
                    pooledRepository(owner, fresh).resolve(request)
                        ?.let { PoolRetryOutcome.Success(it) }
                        ?: PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    PoolRetryOutcome.Failure(classifyPoolFailure(e))
                }
            },
            report = { type -> owner.report(DesktopPlaybackPool.DEEZER, poolId, type) },
        )
    }

    private suspend fun resolvePooled(request: AudioResolveRequest): PreparedAudio? {
        val owner = pool ?: return null
        var failure: Exception? = null
        for (account in owner.deezerAccounts()) {
            val repository = pooledRepository(owner, account)
            try {
                repository.resolve(request)?.let { resolved ->
                    owner.noteSuccess(DesktopPlaybackPool.DEEZER, account.id)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                recoverPooled(owner, account, PoolFailureKind.AUTH_REJECTED, request)?.let { return it }
                owner.noteFailure(DesktopPlaybackPool.DEEZER, account.id)
            } catch (error: Exception) {
                recoverPooled(owner, account, classifyPoolFailure(error), request)?.let { return it }
                owner.noteFailure(DesktopPlaybackPool.DEEZER, account.id)
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }
}

/** QQ Music with the personal ticket when one is accepted, otherwise the anonymous RPC the repository also supports. */
internal class DesktopQqMusicSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
) : AudioProviderRepository {
    override val source = AudioSourceType.QQ
    private val guid = UUID.randomUUID().toString().replace("-", "")

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val scope = providerAccountScope(ContentProvider.QQ.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalQqMusicLease(it) }
        val access = lease?.let {
            QqMusicAccess(it.accountScope, it.uin, it.musicKey.reveal(), it.loginType, CredentialScope.PERSONAL, it::isStillAdmitted)
        }
        return KtorQqMusicRepository(http, access, guid = { guid }).resolve(request)
    }
}

/**
 * JioSaavn over the shared repository and the JVM DES decoder. A host may still override the key
 * material via the `archivetune.jiosaavn.key` system property or ARCHIVETUNE_JIOSAAVN_DES_KEY
 * through [DesktopCredentials]; otherwise the retained default key (exactly as the original app
 * bundles it) is used.
 */
internal fun desktopJioSaavnSource(http: HttpClient): AudioProviderRepository {
    val key = (System.getProperty("archivetune.jiosaavn.key") ?: DesktopCredentials.value("ARCHIVETUNE_JIOSAAVN_DES_KEY"))
        .trim().toByteArray(Charsets.UTF_8).takeIf { it.size == 8 }
        ?: JvmJioSaavnUrlDecoder.DEFAULT_KEY
    return KtorJioSaavnRepository(http, JvmJioSaavnUrlDecoder(key))
}
