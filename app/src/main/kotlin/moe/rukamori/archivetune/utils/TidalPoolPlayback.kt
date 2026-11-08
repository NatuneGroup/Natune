package moe.rukamori.archivetune.utils

import android.content.Context
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.shared.data.provider.TidalRateLimitGate
import moe.rukamori.archivetune.shared.data.provider.resolveTidalPoolAccounts
import moe.rukamori.archivetune.shared.data.provider.tidalRateLimitCooldownMs
import moe.rukamori.archivetune.tidal.TidalAccountManager
import timber.log.Timber
import java.io.File

/** The same bounded account walk for playback and downloads; pool hints never decide entitlement. */
object TidalPoolPlayback {
    private val rateLimits = TidalRateLimitGate { System.currentTimeMillis() }

    private fun key(account: PoolAccountManager.TidalPoolAccount): String =
        account.id?.toString() ?: account.token

    suspend fun noteRateLimit(account: PoolAccountManager.TidalPoolAccount, retryAfterMs: Long?) {
        rateLimits.limit(key(account), retryAfterMs)
        Timber.tag("PoolRecovery").w("TIDAL pool rate limited: cooldownMs=%d; keeping credential, trying next account",
            tidalRateLimitCooldownMs(retryAfterMs))
    }

    suspend fun resolve(
        context: Context,
        title: String,
        artists: List<String>,
        durationMs: Long?,
        audioQuality: String,
        cacheDir: File,
        preferLiveDash: Boolean = false,
    ): DirectStream? {
        if (PoolAccountManager.tidalAccounts().any { TidalAccountManager.isAccessTokenExpired(it.token) }) {
            runCatching { PoolAccountManager.reLeaseExpired(context, "tidal") }
                .onFailure { it.rethrowIfAudioSourceCancelled() }
        }
        return resolveTidalPoolAccounts(
            accounts = PoolAccountManager.tidalAccounts(),
            preferLossless = audioQuality == "LOSSLESS" || audioQuality == "HI_RES_LOSSLESS",
            canAttempt = { account ->
                val allowed = rateLimits.canAttempt(key(account))
                if (!allowed) Timber.tag("PoolRecovery").d("TIDAL pool account skipped: rate-limit cooldown active")
                allowed && AudioSourceAttemptScope.current()?.isExpired() != true
            },
            isLossless = { stream -> stream.mimeType.contains("flac", true) || stream.codecs.orEmpty().contains("flac", true) },
        ) { account ->
            val country = account.countryCode?.trim()?.ifBlank { null } ?: "US"
            val stream = PoolAccountRecovery.resolvePooledTidal(context, account, country) { token, region ->
                TidalAccountManager.resolveDirectStream(
                    accessToken = token, title = title, artists = artists, durationMs = durationMs,
                    audioQuality = audioQuality, cacheDir = cacheDir, countryCode = region,
                    preferLiveDash = preferLiveDash,
                )
            }
            if (stream == null) PoolAccountManager.noteAccountFailure("tidal", account.id)
            else {
                PoolAccountManager.noteAccountSuccess("tidal", account.id)
                Timber.tag("PoolRecovery").i("TIDAL pooled full stream: poolPremiumHint=%s format=%s quality=%s",
                    account.premium, stream.mimeType, stream.label)
            }
            stream
        }
    }
}
