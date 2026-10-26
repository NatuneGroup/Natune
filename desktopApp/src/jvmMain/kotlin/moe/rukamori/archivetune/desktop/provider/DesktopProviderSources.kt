package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import java.util.UUID
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.desktop.DesktopCredentials
import moe.rukamori.archivetune.shared.data.provider.DeezerAccess
import moe.rukamori.archivetune.shared.data.provider.JvmJioSaavnUrlDecoder
import moe.rukamori.archivetune.shared.data.provider.KtorDeezerRepository
import moe.rukamori.archivetune.shared.data.provider.KtorJioSaavnRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorTidalRepository
import moe.rukamori.archivetune.shared.data.provider.ProviderHttpFailure
import moe.rukamori.archivetune.shared.data.provider.QqMusicAccess
import moe.rukamori.archivetune.shared.data.provider.TidalAccess
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.TidalAccountFirstKey
import moe.rukamori.archivetune.shared.domain.settings.tidalCountryCode
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

/**
 * TIDAL over the personal account lease, the Playback Pool accounts and the HiFi instances (the user's own, then the
 * pool's discovered ones), in the shipped order: the accounts first while "account first" is on (the default), otherwise
 * the instances first. Attempts run in that order; the Android resolver races pool accounts, which this host does not do
 * yet. A failed attempt moves to the next one. A terminal authentication failure of the personal account stops the
 * source; a pooled account that is refused is reported dead and the next one is tried. The app ships no TIDAL catalog
 * token: instances search anonymously, and a direct TIDAL id needs an account. With no account, no pool and no instance
 * the source answers no match, and the TIDAL settings page asks the user to sign in.
 */
internal class DesktopTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val sessionRefresh: DesktopTidalSessionRefresh? = null,
    private val pool: DesktopPlaybackPool? = null,
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
            Attempt(
                KtorTidalRepository(
                    http,
                    TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE),
                    instanceBase = base,
                    countryCode = country,
                ),
            )
        }
        val accountFirst = snapshot?.get(TidalAccountFirstKey) ?: true
        val accountAttempts = listOfNotNull(personal) + pooled
        val attempts = if (accountFirst) accountAttempts + instances else instances + accountAttempts
        var failure: Exception? = null
        for (attempt in attempts) {
            try {
                attempt.repository.resolve(request)?.let { resolved ->
                    if (attempt.pooled) pool?.noteSuccess(DesktopPlaybackPool.TIDAL, attempt.poolId)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                if (!attempt.pooled) throw auth
                pool?.report(DesktopPlaybackPool.TIDAL, attempt.poolId, "dead")
                pool?.noteFailure(DesktopPlaybackPool.TIDAL, attempt.poolId)
            } catch (error: Exception) {
                if (attempt.pooled) {
                    if ((error as? ProviderHttpFailure)?.status == 401) pool?.report(DesktopPlaybackPool.TIDAL, attempt.poolId, "dead")
                    pool?.noteFailure(DesktopPlaybackPool.TIDAL, attempt.poolId)
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
internal class DesktopDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val pool: DesktopPlaybackPool? = null,
) : AudioProviderRepository {
    override val source = AudioSourceType.DEEZER
    private var cached: Pair<Long, KtorDeezerRepository>? = null
    private val pooledRepositories = HashMap<String, KtorDeezerRepository>()

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL)
        val lease = scope?.let { accounts.personalDeezerLease(it) } ?: return resolvePooled(request)
        val repository = synchronized(this) {
            cached?.takeIf { it.first == lease.generation }?.second ?: KtorDeezerRepository(
                http,
                nowMs = System::currentTimeMillis,
                access = DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted),
            ).also { cached = lease.generation to it }
        }
        return repository.resolve(request)
    }

    private suspend fun resolvePooled(request: AudioResolveRequest): PreparedAudio? {
        val owner = pool ?: return null
        var failure: Exception? = null
        for (account in owner.deezerAccounts()) {
            val repository = synchronized(this) {
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
            try {
                repository.resolve(request)?.let { resolved ->
                    owner.noteSuccess(DesktopPlaybackPool.DEEZER, account.id)
                    return resolved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                owner.report(DesktopPlaybackPool.DEEZER, account.id, "dead")
                owner.noteFailure(DesktopPlaybackPool.DEEZER, account.id)
            } catch (error: Exception) {
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
 * JioSaavn over the shared repository and the JVM DES decoder. The decoder key is host-supplied material: it is read
 * from the `archivetune.jiosaavn.key` system property, then ARCHIVETUNE_JIOSAAVN_DES_KEY through [DesktopCredentials],
 * and the source is not composed without it, so it answers as unavailable instead of shipping a copied key.
 */
internal fun desktopJioSaavnSource(http: HttpClient): AudioProviderRepository? {
    val key = (System.getProperty("archivetune.jiosaavn.key") ?: DesktopCredentials.value("ARCHIVETUNE_JIOSAAVN_DES_KEY"))
        .trim().toByteArray(Charsets.UTF_8).takeIf { it.size == 8 } ?: return null
    return KtorJioSaavnRepository(http, JvmJioSaavnUrlDecoder(key))
}
