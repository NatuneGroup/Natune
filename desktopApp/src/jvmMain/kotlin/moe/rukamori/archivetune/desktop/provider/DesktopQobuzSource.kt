package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.provider.NoPoolDiscoveryStore
import moe.rukamori.archivetune.shared.data.provider.JvmQobuzRequestDigest
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzAccountIdentity
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzRepository
import moe.rukamori.archivetune.shared.data.provider.QobuzAccess
import moe.rukamori.archivetune.shared.data.provider.QobuzInstanceResolver
import moe.rukamori.archivetune.shared.data.provider.PoolFailureKind
import moe.rukamori.archivetune.shared.data.provider.PoolRetryOutcome
import moe.rukamori.archivetune.shared.data.provider.classifyPoolFailure
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailable
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailableReason
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.UsePoolAccountsKey
import moe.rukamori.archivetune.shared.domain.settings.qobuzInstanceList
import moe.rukamori.archivetune.shared.domain.settings.qobuzTokens
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** Saved API credentials in order, then the user's proxy instances, just like Android's orderedBackends. */
internal class DesktopQobuzSource(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val admissions: DesktopSourceAdmissions,
    private val pool: DesktopPlaybackPool? = null,
    private val identity: suspend (QobuzTokenEntry) -> String? = KtorQobuzAccountIdentity(http)::userId,
) : AudioProviderRepository {
    override val source = AudioSourceType.QOBUZ
    private val gate = Mutex()
    private val digest = JvmQobuzRequestDigest()
    /** One resolver for the session, so its cooldowns and discovery persist. The Pool switch gates its discovery. */
    private val instances = QobuzInstanceResolver(
        http,
        System::currentTimeMillis,
        poolEnabled = { pool?.enabled() == true },
        sourceProviderUrl = { pool?.discoveryBaseUrl().orEmpty() },
        sourceProviderKey = { pool?.discoveryReadKey().orEmpty() },
        discoveryStore = pool?.discoveryStore(DesktopPlaybackPool.QOBUZ) ?: NoPoolDiscoveryStore,
    )
    private class Cached(val lease: DesktopSourceAdmissions.Lease, val repository: KtorQobuzRepository)
    private data class CacheKey(val entry: QobuzTokenEntry, val pooled: Boolean)
    private class Credential(val key: CacheKey, val scope: CredentialScope, val current: () -> Boolean,
        val poolId: Long? = null)
    private val repositories = LinkedHashMap<CacheKey, Cached>()

    private fun savedEntries(): List<QobuzTokenEntry> {
        val prefs = preferences() ?: return emptyList()
        return prefs.qobuzTokens().filter { it.poolId == null || prefs[UsePoolAccountsKey] != false }
    }

    private suspend fun entries(): List<Credential> = savedEntries().map { entry ->
        Credential(CacheKey(entry, false),
            if (entry.poolId == null) CredentialScope.PERSONAL else CredentialScope.PLAYBACK_POOL,
            { entry in savedEntries() })
    } + pool?.qobuzAccounts().orEmpty().map { account ->
        val entry = QobuzTokenEntry(account.token, appId = account.appId, appSecret = account.appSecret, poolId = account.id)
        Credential(CacheKey(entry, true), CredentialScope.PLAYBACK_POOL, { pool?.admitsQobuz(account) == true }, account.id)
    }

    private suspend fun repository(credential: Credential): KtorQobuzRepository? = gate.withLock {
        repositories.entries.removeIf { !admissions.active(it.value.lease) }
        repositories[credential.key]?.let { return@withLock it.repository }
        if (!credential.current()) return@withLock null
        val entry = credential.key.entry
        val userId = identity(entry) ?: return@withLock null
        val scope = credential.scope
        val lease = admissions.accept(ProviderIdentity(ContentProvider.QOBUZ.id, scope, userId),
            credential.key, credential.current) ?: return@withLock null
        val repository = KtorQobuzRepository(http, System::currentTimeMillis,
            QobuzAccess(lease.scope, entry.appId, entry.token, entry.appSecret, scope) { admissions.active(lease) },
            digest)
        repositories[credential.key] = Cached(lease, repository)
        repository
    }

    /**
     * Recovers a pooled Qobuz account that just failed: one re-lease (the pool can rotate
     * tokens, appIds and appSecrets server-side) and one retry with the credential it now
     * serves; the account is reported dead only when it still fails for real.
     */
    private suspend fun recoverPooled(
        credential: Credential,
        kind: PoolFailureKind,
        request: AudioResolveRequest,
    ): PreparedAudio? {
        val owner = pool ?: return null
        val poolId = credential.poolId ?: return null
        return owner.deadReports.recoverAndReport(
            DesktopPlaybackPool.QOBUZ, poolId, kind,
            stale = credential.key.entry.let { Triple(it.token, it.appId, it.appSecret) },
            current = { owner.qobuzSnapshot().firstOrNull { it.id == poolId }?.let { Triple(it.token, it.appId, it.appSecret) } },
            recover = { owner.refresh(force = true) },
            retry = {
                val account = owner.qobuzSnapshot().firstOrNull { it.id == poolId }
                    ?: return@recoverAndReport PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                val entry = QobuzTokenEntry(account.token, appId = account.appId,
                    appSecret = account.appSecret, poolId = account.id)
                try {
                    repository(Credential(CacheKey(entry, true), CredentialScope.PLAYBACK_POOL,
                        { owner.admitsQobuz(account) }, account.id))
                        ?.resolve(request)
                        ?.let { PoolRetryOutcome.Success(it) }
                        ?: PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    PoolRetryOutcome.Failure(classifyPoolFailure(e))
                }
            },
            report = { type -> owner.report(DesktopPlaybackPool.QOBUZ, poolId, type) },
        )
    }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        var failure: Exception? = null
        val saved = entries()
        for (credential in saved) {
            try {
                repository(credential)?.resolve(request)?.let {
                    if (credential.key.pooled) pool?.noteSuccess(DesktopPlaybackPool.QOBUZ, credential.poolId)
                    return it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (credential.key.pooled) {
                    recoverPooled(credential, classifyPoolFailure(error), request)?.let { return it }
                    pool?.noteFailure(DesktopPlaybackPool.QOBUZ, credential.poolId)
                }
                failure = error
            }
        }
        val bases = preferences()?.qobuzInstanceList().orEmpty()
        // The user's list is the resolver's manual list. Syncing it here keeps a removed base from lingering.
        instances.setInstances(bases)
        // Pool-discovered instances count as reachable only while the Pool switch is on, through the resolver itself.
        if (bases.isNotEmpty() || instances.hasInstances()) {
            KtorQobuzRepository(http, System::currentTimeMillis, resolver = instances, instances = {
                preferences()?.qobuzInstanceList().orEmpty()
            }).resolve(request)?.let { return it }
        }
        failure?.let { throw it }
        if (saved.isEmpty() && !instances.hasInstances()) throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        return null
    }

    suspend fun search(query: String): List<ContentItem> {
        val request = ContentRequest(ContentProvider.QOBUZ, ContentSurface.SEARCH,
            text = query, filter = ContentSearchFilter.SONGS)
        for (credential in entries()) {
            try {
                val rows = repository(credential)?.page(request, null)?.items.orEmpty().map { it.item }
                if (rows.isNotEmpty()) return rows
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        for (base in preferences()?.qobuzInstanceList().orEmpty()) {
            try {
                val rows = KtorQobuzRepository(http, System::currentTimeMillis, instanceBase = base)
                    .page(request, null).items.map { it.item }
                if (rows.isNotEmpty()) return rows
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    suspend fun close() = gate.withLock { repositories.clear() }
}
