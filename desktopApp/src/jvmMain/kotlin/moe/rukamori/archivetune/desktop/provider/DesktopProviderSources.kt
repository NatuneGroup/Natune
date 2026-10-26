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
 * TIDAL over the personal account lease and the user's HiFi instances, in the shipped order: the account first while
 * "account first" is on (the default), otherwise the instances first. Instances are tried in their saved order; the
 * Android resolver races them, which this host does not do yet. A failed attempt moves to the next one, and a terminal
 * authentication failure stops the source. The instances need the public web token (ARCHIVETUNE_TIDAL_WEB_TOKEN); a
 * build without it leaves them not configured and only the personal account is tried.
 */
internal class DesktopTidalSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.TIDAL

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val snapshot = preferences()
        val country = snapshot?.tidalCountryCode()?.takeIf { it.length == 2 } ?: "US"
        val personal = personalRepository(country)
        val webToken = DesktopCredentials.tidalWebToken
        val instances = if (webToken == null) emptyList() else snapshot?.tidalInstanceList().orEmpty().map { base ->
            KtorTidalRepository(
                http,
                TidalAccess(scope = ANONYMOUS_ACCOUNT_SCOPE, catalogToken = webToken),
                instanceBase = base,
                countryCode = country,
            )
        }
        val accountFirst = snapshot?.get(TidalAccountFirstKey) ?: true
        val attempts = if (accountFirst) listOfNotNull(personal) + instances else instances + listOfNotNull(personal)
        var failure: Exception? = null
        for (repository in attempts) {
            try {
                repository.resolve(request)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: TerminalPlaybackAuthentication) {
                throw auth
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
        return null
    }

    private suspend fun personalRepository(country: String): KtorTidalRepository? {
        val scope = providerAccountScope(ContentProvider.TIDAL.id, CredentialScope.PERSONAL) ?: return null
        val lease = accounts.personalTidalLease(scope) ?: return null
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
 * Deezer over the personal ARL lease. Deezer media needs a signed-in session, so without a lease the source answers no
 * match. One repository is kept per lease generation, so its gateway session is reused until the account changes.
 */
internal class DesktopDeezerSource(
    private val http: HttpClient,
    private val accounts: VaultProviderAccountStore,
) : AudioProviderRepository {
    override val source = AudioSourceType.DEEZER
    private var cached: Pair<Long, KtorDeezerRepository>? = null

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL) ?: return null
        val lease = accounts.personalDeezerLease(scope) ?: return null
        val repository = synchronized(this) {
            cached?.takeIf { it.first == lease.generation }?.second ?: KtorDeezerRepository(
                http,
                nowMs = System::currentTimeMillis,
                access = DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted),
            ).also { cached = lease.generation to it }
        }
        return repository.resolve(request)
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
