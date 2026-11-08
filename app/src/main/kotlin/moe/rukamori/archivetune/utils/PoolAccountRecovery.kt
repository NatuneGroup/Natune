package moe.rukamori.archivetune.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReportPolicy
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReporter
import moe.rukamori.archivetune.shared.data.provider.PoolFailureKind
import moe.rukamori.archivetune.shared.data.provider.PoolRecovery
import moe.rukamori.archivetune.shared.data.provider.PoolRetryOutcome
import moe.rukamori.archivetune.shared.data.provider.ProviderHttpFailure
import moe.rukamori.archivetune.shared.data.provider.TidalFailureKind
import moe.rukamori.archivetune.shared.data.provider.TidalPlaybackFailure
import moe.rukamori.archivetune.shared.data.provider.classifyPoolFailure
import moe.rukamori.archivetune.tidal.TidalAccountManager
import timber.log.Timber

/**
 * The Android host's half of the shared [PoolDeadReporter] rules. A pooled account the provider
 * just refused is usually only holding a stale lease — pooled Tidal access tokens live ~an hour
 * while the feed cache lives a day — so a refusal buys one re-lease, single-flight per account,
 * then one retry with the credential the server now serves. A dead report fires only when the
 * recovered credential is refused again for real: revoked, banned or entitlement gone. This is
 * what used to turn healthy accounts into ~2,000 false dead reports a day and made the pool
 * hammer Tidal's refresh endpoint until it answered `abuse_detected`.
 */
object PoolAccountRecovery {
    private const val TAG = "PoolRecovery"

    private val reporter = PoolDeadReporter(nowMs = { System.currentTimeMillis() })

    /** The application context an explicit [explicit] argument or the App singleton provides. */
    private fun application(explicit: Context?): Context? =
        explicit?.applicationContext ?: runCatching { App.instance.applicationContext }.getOrNull()

    /**
     * Re-leases the Source Pool once for (service, id): single-flight per account, throttled to one
     * feed fetch per recovery window, and waiters reuse the winner's fresh cache. [current] reads
     * only the cached credential — never the network; the network round-trip lives solely inside
     * the gated recovery callback. The answer is explicit: [PoolRecovery.Recovered] carries the
     * credential the feed now serves, [PoolRecovery.Unchanged] means the same credential came back
     * and [PoolRecovery.Unavailable] means no round-trip ran — neither justifies a dead verdict.
     */
    suspend fun <C : Any> reLease(
        context: Context?,
        service: String,
        id: Long?,
        stale: C,
        current: suspend () -> C?,
    ): PoolRecovery<C> {
        val application = application(context) ?: return PoolRecovery.Unavailable
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
    ): PoolRecovery<C> = runBlocking(Dispatchers.IO) { reLease(context, service, id, stale) { current() } }

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
     * Maps a Tidal resolve failure onto the shared failure kinds. The typed auth rejection is a
     * [TidalAccountManager.TidalUnauthorizedException]; every other HTTP refusal surfaces as a
     * typed [TidalPlaybackFailure]. Subscription ceilings keep the token, genuine auth refusals
     * earn recovery, and rate limits suppress subsequent requests without a dead report.
     */
    private suspend fun tidalFailureKind(root: Throwable?, account: PoolAccountManager.TidalPoolAccount): PoolFailureKind {
        if (TidalAccountManager.isUnauthorized(root)) return PoolFailureKind.AUTH_REJECTED
        val stack = ArrayDeque<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        root?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val t = stack.removeLast()
            if (!seen.add(t)) continue
            if (t is TidalPlaybackFailure) return when (t.kind) {
                TidalFailureKind.QUALITY_RESTRICTED -> {
                    Timber.tag(TAG).w("TIDAL pool has no allowed full-stream quality; keeping credential (poolPremiumHint=%s)", account.premium)
                    PoolFailureKind.CONTENT_UNAVAILABLE
                }
                TidalFailureKind.AUTH_REJECTED -> PoolFailureKind.AUTH_REJECTED
                TidalFailureKind.ENTITLEMENT_LOST -> PoolFailureKind.ENTITLEMENT_LOST
                TidalFailureKind.RATE_LIMITED -> {
                    TidalPoolPlayback.noteRateLimit(account, t.retryAfterMs)
                    PoolFailureKind.RATE_LIMITED
                }
                TidalFailureKind.CONTENT_UNAVAILABLE -> PoolFailureKind.CONTENT_UNAVAILABLE
                TidalFailureKind.TRANSIENT -> PoolFailureKind.TRANSIENT
            }
            if (t is ProviderHttpFailure) return classifyPoolFailure(t)
            t.cause?.let { stack.addLast(it) }
            t.suppressed.forEach { stack.addLast(it) }
        }
        return PoolFailureKind.TRANSIENT
    }

    /**
     * Resolves through one pooled Tidal account the way the personal-token path already recovers:
     * a credential refusal (401/403) buys one re-lease, then one retry with the credential the
     * server now serves; only the retry's own rejection earns a verdict — `dead` on a second auth
     * refusal, `not_premium` on a 402. A lease that did not move, a recovery that could not run
     * and transient failures all leave the pool alone. Answers the stream or null.
     */
    suspend fun resolvePooledTidal(
        context: Context?,
        account: PoolAccountManager.TidalPoolAccount,
        country: String,
        attempt: suspend (token: String, country: String) -> DirectStream?,
    ): DirectStream? {
        val first = runCatching { attempt(account.token, country) }
        first.exceptionOrNull()?.rethrowIfAudioSourceCancelled()
        if (first.isSuccess) return first.getOrNull()
        val kind = tidalFailureKind(first.exceptionOrNull(), account)
        if (kind != PoolFailureKind.AUTH_REJECTED && kind != PoolFailureKind.ENTITLEMENT_LOST) {
            Timber.tag(TAG).w(first.exceptionOrNull(), "Tidal pool account %s resolve failed", account.id)
            return null
        }
        val application = application(context) ?: return null
        return reporter.recoverAndReport(
            "tidal", account.id, kind,
            stale = account.token,
            current = { PoolAccountManager.tidalAccounts().firstOrNull { it.id == account.id }?.token },
            recover = { PoolAccountManager.refresh(application, force = true) },
            retry = { fresh ->
                val retried = runCatching { attempt(fresh, country) }
                retried.exceptionOrNull()?.rethrowIfAudioSourceCancelled()
                when {
                    retried.isSuccess -> retried.getOrNull()
                        ?.let { PoolRetryOutcome.Success(it) }
                        ?: PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
                    else -> PoolRetryOutcome.Failure(tidalFailureKind(retried.exceptionOrNull(), account))
                }
            },
            report = { type -> PoolAccountManager.report("tidal", "account", account.id, type) },
        )
    }

    /**
     * Re-leases once after a 401/403 on a pooled Qobuz credential and runs [retry] once with
     * whatever the feed now serves. The pool can rotate the user token, the appId and the
     * appSecret independently, so the moved-lease check compares the whole tuple. Only a retry
     * whose own refusal is typed auth (the retried call throws [ProviderHttpFailure] on a second
     * 401/403) earns the dead report; any other outcome leaves the pool alone.
     */
    fun <R : Any> retryOrReportQobuz(
        poolId: Long,
        token: QobuzToken,
        retry: (QobuzToken) -> R?,
    ): R? {
        val recovery = reLeaseBlocking(
            null, "qobuz", poolId,
            stale = Triple(token.token, token.appId, token.appSecret),
        ) {
            PoolAccountManager.qobuzAccounts().firstOrNull { it.id == poolId }
                ?.let { Triple(it.token, it.appId, it.appSecret) }
        }
        if (recovery !is PoolRecovery.Recovered) return null
        val credential = recovery.credential
        val retried =
            runCatching {
                retry(
                    QobuzToken(
                        token = credential.first,
                        userId = token.userId,
                        appId = credential.second,
                        appSecret = credential.third,
                        label = token.label,
                        subscription = token.subscription,
                        poolId = poolId,
                    ),
                )
            }
        retried.exceptionOrNull()?.rethrowIfAudioSourceCancelled()
        return when {
            retried.isSuccess -> retried.getOrNull()
            classifyPoolFailure(retried.exceptionOrNull()) == PoolFailureKind.AUTH_REJECTED -> {
                reportDead("qobuz", poolId)
                null
            }
            else -> null
        }
    }
}
