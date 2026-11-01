package moe.rukamori.archivetune.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReportPolicy
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReporter
import moe.rukamori.archivetune.tidal.TidalAccountManager
import timber.log.Timber

/**
 * The Android host's half of the shared [PoolDeadReporter] rules. A pooled account the provider
 * just refused is usually only holding a stale lease — pooled Tidal access tokens live ~an hour
 * while the feed cache lives a day — so a refusal buys one re-lease, single-flight per account,
 * then one retry with the credential the server now serves. A dead report fires only when the
 * credential still fails for real afterwards: revoked, banned or entitlement gone. This is what
 * used to turn healthy accounts into ~2,000 false dead reports a day and made the pool hammer
 * Tidal's refresh endpoint until it answered `abuse_detected`.
 */
object PoolAccountRecovery {
    private const val TAG = "PoolRecovery"

    private val reporter = PoolDeadReporter(nowMs = { System.currentTimeMillis() })

    /** The application context an explicit [explicit] argument or the App singleton provides. */
    private fun application(explicit: Context?): Context? =
        explicit?.applicationContext ?: runCatching { App.instance.applicationContext }.getOrNull()

    /**
     * Re-leases the Source Pool once for (service, id): single-flight per account, throttled to one
     * feed fetch per recovery window, and waiters reuse the winner's fresh cache. [current] re-reads
     * the credential the feed now serves for the account; the lease counts as moved only when it
     * differs from [stale]. Answers the fresh credential, or null when the lease did not move (no
     * context, a failed fetch, or the same credential came back).
     */
    suspend fun <C : Any> reLease(
        context: Context?,
        service: String,
        id: Long?,
        stale: C,
        current: suspend () -> C?,
    ): C? {
        val application = application(context) ?: return null
        return reporter.reLease(service, id, stale, current) {
            PoolAccountManager.refresh(application, force = true)
        }
    }

    /** [reLease] for call sites already on a blocking thread. */
    fun <C : Any> reLeaseBlocking(
        context: Context?,
        service: String,
        id: Long?,
        stale: C,
        current: () -> C?,
    ): C? = runBlocking(Dispatchers.IO) { reLease(context, service, id, stale) { current() } }

    /**
     * Reports a dead pooled account through the pool's own fire-and-forget channel — so the
     * merged-replacement path still runs — at most once per account and type inside
     * [PoolDeadReportPolicy.REPORT_DEDUPE_MS].
     */
    fun reportDead(service: String, id: Long?, type: String = PoolDeadReportPolicy.DEAD) {
        runBlocking(Dispatchers.IO) {
            reporter.report(service, id, type) { PoolAccountManager.report(service, "account", id, type) }
        }
    }

    /**
     * Resolves through one pooled Tidal account the way the personal-token path already recovers:
     * a 401 buys one re-lease, then one retry with the credential the server now serves; only a
     * second refusal reports the account dead. Non-auth failures leave the pool alone. Answers the
     * stream or null.
     */
    suspend fun resolvePooledTidal(
        context: Context?,
        account: PoolAccountManager.TidalPoolAccount,
        country: String,
        attempt: suspend (token: String, country: String) -> DirectStream?,
    ): DirectStream? {
        val first = runCatching { attempt(account.token, country) }
        if (first.isSuccess) return first.getOrNull()
        if (!TidalAccountManager.isUnauthorized(first.exceptionOrNull())) {
            Timber.tag(TAG).w(first.exceptionOrNull(), "Tidal pool account %s resolve failed", account.id)
            return null
        }
        val freshToken = reLease(context, "tidal", account.id, stale = account.token) {
            PoolAccountManager.tidalAccounts().firstOrNull { it.id == account.id }?.token
        }
        if (freshToken != null) {
            val retried = runCatching { attempt(freshToken, country) }
            if (retried.isSuccess) return retried.getOrNull()
            if (!TidalAccountManager.isUnauthorized(retried.exceptionOrNull())) {
                Timber.tag(TAG).w(retried.exceptionOrNull(), "Tidal pool account %s retry failed", account.id)
                return null
            }
        }
        reportDead("tidal", account.id)
        return null
    }

    /**
     * Re-leases once after a 401/403 on a pooled Qobuz token and runs [retry] with whatever the feed
     * now serves. The retried call applies the same recovery on a second refusal; when the lease
     * never moved, the credential the server still believes healthy is the one that just failed, so
     * it is reported dead (deduped).
     */
    fun <R : Any> retryOrReportQobuz(
        poolId: Long,
        token: QobuzToken,
        retry: (QobuzToken) -> R?,
    ): R? {
        val freshToken = reLeaseBlocking(null, "qobuz", poolId, stale = token.token) {
            PoolAccountManager.qobuzAccounts().firstOrNull { it.id == poolId }?.token
        }
        if (freshToken == null) {
            reportDead("qobuz", poolId)
            return null
        }
        val account = PoolAccountManager.qobuzAccounts().firstOrNull { it.id == poolId }
        return runCatching {
            retry(
                QobuzToken(
                    token = freshToken,
                    appId = account?.appId ?: token.appId,
                    appSecret = account?.appSecret ?: token.appSecret,
                    label = token.label,
                    subscription = token.subscription,
                    poolId = poolId,
                ),
            )
        }.getOrNull()
    }
}
