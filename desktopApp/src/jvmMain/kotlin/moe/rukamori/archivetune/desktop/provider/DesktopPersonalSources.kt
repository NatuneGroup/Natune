package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.desktop.DesktopCredentials
import moe.rukamori.archivetune.shared.data.provider.AmazonInstanceAccess
import moe.rukamori.archivetune.shared.data.provider.AppleMusicAccess
import moe.rukamori.archivetune.shared.data.provider.KtorAmazonRepository
import moe.rukamori.archivetune.shared.data.provider.KtorAppleMusicRepository
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailable
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailableReason
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.settings.AmazonBypassTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AmazonTurnstileJwtExpiryMsKey
import moe.rukamori.archivetune.shared.domain.settings.AmazonTurnstileJwtKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicDevTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

internal class DesktopAppleMusicSource(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.APPLE

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val prefs = preferences()
        val developer = prefs?.get(AppleMusicDevTokenKey)?.takeIf { it.isNotBlank() }
        val user = prefs?.get(AppleMusicMediaUserTokenKey)?.takeIf { it.isNotBlank() }
        if (developer == null || user == null) throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        val access = AppleMusicAccess(CredentialScope.PERSONAL.name, developer, user,
            credentialScope = CredentialScope.PERSONAL, isActive = {
                preferences()?.let { it[AppleMusicDevTokenKey] == developer && it[AppleMusicMediaUserTokenKey] == user } == true
            })
        val prepared = KtorAppleMusicRepository(http, access, nowMs = System::currentTimeMillis).resolve(request)
        // Neither the desktop engine nor the cookie-only browser is a licensed Apple content-decryption module.
        // Never hand its encrypted HLS URL to FFmpeg as progressive audio.
        if (prepared is PreparedAudio.Native && prepared.recipe is NativeAudioRecipe.AppleHls) {
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        }
        return prepared
    }
}

internal class AmazonSourceAuthorization(val operator: String?, val jwt: String?, val expiresAtMs: Long?) {
    override fun toString() = "AmazonSourceAuthorization(REDACTED)"
}

internal fun desktopAmazonAuthorization(preferences: PreferenceSnapshot?, nowMs: Long): AmazonSourceAuthorization? {
    val operator = preferences?.get(AmazonBypassTokenKey)?.takeIf { it.isNotBlank() }
    val expiry = preferences?.get(AmazonTurnstileJwtExpiryMsKey)
    val jwt = preferences?.get(AmazonTurnstileJwtKey)?.takeIf { it.isNotBlank() && expiry != null && expiry > nowMs }
    return if (operator == null && jwt == null) null else AmazonSourceAuthorization(operator, jwt, expiry)
}

/** Authorized instance resolution, not an Amazon subscriber cookie or a catalog-account sign-in. */
internal class DesktopAmazonSource(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pool: DesktopPlaybackPool? = null,
    private val admissions: DesktopSourceAdmissions? = null,
) : AudioProviderRepository {
    override val source = AudioSourceType.AMAZON

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val bases = preferences()?.amazonInstanceList().orEmpty()
        val personalAuthorization = desktopAmazonAuthorization(preferences(), nowMs())
        // Sent as the Origin the sitekey is locked to; a build that names none sends no Origin header, as Android does.
        val turnstileOrigin = DesktopCredentials.amazonTurnstileOrigin
        var failure: Exception? = null
        var authorized = personalAuthorization != null
        for ((index, base) in (if (personalAuthorization == null) emptyList() else bases).withIndex()) {
            val authorization = requireNotNull(personalAuthorization)
            // A JWT is issued by the first instance's authorization flow and never sent to another host.
            val jwt = authorization.jwt.takeIf { index == 0 }
            if (authorization.operator == null && jwt == null) continue
            val access = AmazonInstanceAccess(
                // The instance, like a public TIDAL/Qobuz proxy, returns a CDN URL and possibly a content key.
                // Its operator/JWT authorizes only the resolver API; no Amazon user identity or CDN cookie exists.
                scope = ANONYMOUS_ACCOUNT_SCOPE,
                operatorToken = authorization.operator,
                turnstileJwt = jwt,
                turnstileJwtExpiresAtMs = authorization.expiresAtMs,
                isActive = {
                    val current = desktopAmazonAuthorization(preferences(), nowMs())
                    base in preferences()?.amazonInstanceList().orEmpty() && current != null &&
                        current.operator == authorization.operator && (jwt == null ||
                        current.jwt == jwt && preferences()?.amazonInstanceList()?.firstOrNull() == base)
                },
            )
            try {
                KtorAmazonRepository(http, base, access, nowMs, turnstileOrigin = turnstileOrigin)
                    .resolve(request)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        val pooled = pool?.amazonAccounts().orEmpty()
        for (account in pooled) {
            val owner = requireNotNull(pool)
            val jwt = account.jwt?.takeIf { moe.rukamori.archivetune.shared.data.provider.AmazonInstances.isJwtUsable(account.expiry, nowMs()) }
            if (account.operator == null && jwt == null) continue
            val lease = admissions?.accept(moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity("amazon",
                CredentialScope.PLAYBACK_POOL, "pool:${account.id ?: account.baseUrl.hashCode()}"), account) { owner.amazonActive(account) }
                ?: continue
            authorized = true
            val access = AmazonInstanceAccess(lease.scope, account.operator, jwt, account.expiry, isActive = { owner.amazonActive(account) })
            try {
                KtorAmazonRepository(http, account.baseUrl, access, nowMs, turnstileOrigin = turnstileOrigin).resolve(request)?.let {
                    owner.noteSuccess(DesktopPlaybackPool.AMAZON, account.id)
                    return it
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { owner.noteFailure(DesktopPlaybackPool.AMAZON, account.id); failure = error }
        }
        failure?.let { throw it }
        if (!authorized) throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        return null
    }
}
