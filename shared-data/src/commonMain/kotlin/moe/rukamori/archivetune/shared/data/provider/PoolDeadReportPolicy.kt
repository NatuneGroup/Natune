package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication

/**
 * Why one pooled-account attempt failed. Only the kinds that mean the account itself is dead may
 * ever be reported to the pool; everything else is a blip the next account or the next request
 * absorbs, and reporting it just burns a healthy account for everyone leasing it.
 */
enum class PoolFailureKind {
    /** The provider refused the credential itself (HTTP 401/403, terminal playback auth). */
    AUTH_REJECTED,

    /** The account works but lost the plan the pool leases it for (HTTP 402, preview-only). */
    ENTITLEMENT_LOST,

    /** The provider asked clients to slow down (HTTP 429). Hammering is what bans accounts. */
    RATE_LIMITED,

    /** The track or region is unavailable through this account (HTTP 404). Not an account fact. */
    CONTENT_UNAVAILABLE,

    /** Network error, timeout, protocol junk or HTTP 5xx — nothing is known about the account. */
    TRANSIENT,
}

/**
 * Maps a resolver failure to a [PoolFailureKind]. The shared provider exception types cover every
 * host; hosts whose auth failures arrive as other exception types classify them into
 * [PoolFailureKind.AUTH_REJECTED] themselves.
 */
fun classifyPoolFailure(error: Throwable?): PoolFailureKind = when (error) {
    is TerminalPlaybackAuthentication -> PoolFailureKind.AUTH_REJECTED
    is AppleAuthException -> PoolFailureKind.AUTH_REJECTED
    is ProviderHttpFailure -> when (error.status) {
        401, 403 -> PoolFailureKind.AUTH_REJECTED
        402 -> PoolFailureKind.ENTITLEMENT_LOST
        404 -> PoolFailureKind.CONTENT_UNAVAILABLE
        429 -> PoolFailureKind.RATE_LIMITED
        else -> PoolFailureKind.TRANSIENT
    }
    else -> PoolFailureKind.TRANSIENT
}

/** The dead-report rules every host applies identically. */
object PoolDeadReportPolicy {
    /** At most one report per (service, account, type) is posted in this window. */
    const val REPORT_DEDUPE_MS = 30L * 60L * 1000L

    /** How often one account's failure may pay for a recovery (refresh/re-lease) round-trip. */
    const val RECOVERY_DEDUPE_MS = 5L * 60L * 1000L

    const val DEAD = "dead"
    const val NOT_PREMIUM = "not_premium"

    /** The report a settled [kind] earns, or null when the failure never means a dead account. */
    fun reportTypeFor(kind: PoolFailureKind): String? = when (kind) {
        PoolFailureKind.AUTH_REJECTED -> DEAD
        PoolFailureKind.ENTITLEMENT_LOST -> NOT_PREMIUM
        else -> null
    }

    /** Whether [kind] is worth one recovery round-trip before the report is believed. */
    fun needsRecovery(kind: PoolFailureKind): Boolean = kind == PoolFailureKind.AUTH_REJECTED
}

/**
 * At-most-once-per-window admission, keyed by "service:id:type". Shared by report dedupe and the
 * recovery throttle; every host keeps its own instances so a desktop resolve and an iOS resolve
 * cannot suppress each other.
 */
class PoolReportGate(
    private val nowMs: () -> Long,
    private val windowMs: Long = PoolDeadReportPolicy.REPORT_DEDUPE_MS,
) {
    private val lock = Mutex()
    private val admittedAt = HashMap<String, Long>()

    /** True the first time (service, id, type) asks inside a window; entries prune lazily. */
    suspend fun allow(service: String, id: Long?, type: String): Boolean {
        if (id == null || id <= 0L) return false
        return admit("$service:$id:$type")
    }

    /** True the first time (service, type) asks inside a window, across every account. */
    suspend fun allowService(service: String, type: String): Boolean = admit("$service:$type")

    /** Removes an admission: the caller that took it never finished the work it paid for. */
    suspend fun rollback(service: String, id: Long?, type: String) {
        if (id == null || id <= 0L) return
        lock.withLock { admittedAt.remove("$service:$id:$type") }
    }

    /** Removes a service-scoped admission. */
    suspend fun rollbackService(service: String, type: String) {
        lock.withLock { admittedAt.remove("$service:$type") }
    }

    private suspend fun admit(key: String): Boolean = lock.withLock {
        val now = nowMs()
        val last = admittedAt[key]
        if (last != null && now - last < windowMs) return@withLock false
        admittedAt[key] = now
        admittedAt.entries.removeAll { now - it.value > windowMs }
        true
    }
}

/** What one retried resolve settled on after a recovered credential. */
sealed interface PoolRetryOutcome<out R> {
    /** The retry resolved; [value] is handed back to the caller's caller. */
    data class Success<R>(val value: R) : PoolRetryOutcome<R>

    /** The retry failed; [kind] decides whether that now means a dead account. */
    data class Failure(val kind: PoolFailureKind) : PoolRetryOutcome<Nothing>
}

/**
 * What one recovery round-trip actually produced. Only [Recovered] proves the server re-leased a
 * live credential; [Unchanged] means the feed still serves the same credential — it believes the
 * account healthy, so the refusal the caller saw was the old token, not a dead account — and
 * [Unavailable] means no recovery round-trip completed at all (no context, a throttled or failed
 * feed fetch, a skipped gate). Neither justifies reporting an account dead.
 */
sealed interface PoolRecovery<out C> {
    /** The lease moved: [credential] is what the server now serves for the account. */
    data class Recovered<C>(val credential: C) : PoolRecovery<C>

    /** The lease still serves the same credential it was tried with. */
    object Unchanged : PoolRecovery<Nothing>

    /** No recovery could run or the current credential could not be read back. */
    object Unavailable : PoolRecovery<Nothing>
}

/**
 * The dead-report decision for one pooled account, shared by every host (and reusable by pooled
 * race helpers such as racePoolAttempts): a credential refusal first earns one recovery — the
 * caller's [recover] refreshes the token or re-leases the pool so the current server credential is
 * what gets judged — then the resolve is retried exactly once through [retry]. A report fires only
 * for failure classes that mean the account is really dead: the lease refused to move
 * (revoked/banned/subscription gone), or the fresh credential was refused again. Expired-token
 * 401s before a refresh, network errors, timeouts, region or track unavailability and rate limits
 * never produce a report.
 *
 * [stale] and the value [current] re-reads are opaque credential markers (usually the token
 * string); a recovery "moved" the lease only when the marker changed. Recovery is single-flight
 * per account and throttled to one round-trip per service per [recoveryWindowMs], and reports
 * dedupe to one per account and type per [reportWindowMs] — concurrent failures cause one refresh
 * and at most one report.
 */
class PoolDeadReporter(
    private val nowMs: () -> Long,
    reportWindowMs: Long = PoolDeadReportPolicy.REPORT_DEDUPE_MS,
    recoveryWindowMs: Long = PoolDeadReportPolicy.RECOVERY_DEDUPE_MS,
) {
    private val reportGate = PoolReportGate(nowMs, reportWindowMs)
    private val recoveryGate = PoolReportGate(nowMs, recoveryWindowMs)
    private val flights = KeyedSingleFlight()

    /** Runs [post] at most once per (service, id, type) inside the report window. */
    suspend fun report(service: String, id: Long?, type: String, post: suspend () -> Unit) {
        if (reportGate.allow(service, id, type)) post()
    }

    /**
     * Runs [recover] at most once at a time per account and at most once per recovery window per
     * service — one feed fetch refreshes every account a provider serves, so a second account's
     * failure reuses the same round-trip. Callers that queued behind an in-flight recovery re-check
     * [current] inside the flight and reuse the winner's result instead of recovering again — the
     * tidalapi compare-inside-lock pattern. The answer is explicit: [PoolRecovery.Recovered] when the lease moved,
     * [PoolRecovery.Unchanged] when it did not, [PoolRecovery.Unavailable] when no round-trip
     * could run. A leader cancelled inside [recover] gives its admission back so a queued waiter
     * can take over immediately instead of waiting out the window.
     */
    suspend fun <C : Any> reLease(
        service: String,
        id: Long?,
        stale: C,
        current: suspend () -> C?,
        recover: suspend () -> Unit,
    ): PoolRecovery<C> = flights.run(
        key = "$service:${id ?: 0L}",
        satisfied = { current()?.takeIf { it != stale }?.let { PoolRecovery.Recovered(it) } },
        block = {
            // Only the first caller inside the window pays for the round-trip; a caller that
            // arrives after a completed run just re-reads whatever it left.
            if (recoveryGate.allowService(service, "recover")) {
                try {
                    recover()
                } catch (cancelled: CancellationException) {
                    recoveryGate.rollbackService(service, "recover")
                    throw cancelled
                } catch (_: Exception) {
                    // A failed round-trip leaves the marker read to answer Unavailable/Unchanged.
                }
            }
            val marker = runCatching { current() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            when {
                marker == null -> PoolRecovery.Unavailable
                marker == stale -> PoolRecovery.Unchanged
                else -> PoolRecovery.Recovered(marker)
            }
        },
    )

    /**
     * Decides one pooled-account failure of [kind] against [stale], the credential that just
     * failed. Returns the retry's [PoolRetryOutcome.Success.value] when a fresh credential fixed
     * the failure — the caller returns it as its own result and notes success — else null. The
     * entitlement half ([PoolFailureKind.ENTITLEMENT_LOST]) reports immediately: the credential
     * worked, no recovery can change what the account's plan lacks, and the server verifies the
     * claim before believing it.
     */
    suspend fun <C : Any, R : Any> recoverAndReport(
        service: String,
        id: Long?,
        kind: PoolFailureKind,
        stale: C,
        current: suspend () -> C?,
        recover: suspend () -> Unit,
        retry: suspend (C) -> PoolRetryOutcome<R>,
        report: suspend (type: String) -> Unit,
    ): R? {
        if (kind == PoolFailureKind.ENTITLEMENT_LOST) {
            report(service, id, PoolDeadReportPolicy.NOT_PREMIUM) { report(PoolDeadReportPolicy.NOT_PREMIUM) }
            return null
        }
        if (!PoolDeadReportPolicy.needsRecovery(kind)) return null
        val recovery = reLease(service, id, stale, current, recover)
        // Only a moved lease earns a verdict: the credential the server now serves is retried
        // exactly once, and only the retry's own rejection — another auth refusal, or an
        // entitlement loss — is real evidence the account is dead. An unmoved lease just means
        // the old token was stale; an unavailable recovery proves nothing.
        if (recovery !is PoolRecovery.Recovered) return null
        return when (val outcome = retry(recovery.credential)) {
            is PoolRetryOutcome.Success -> outcome.value
            is PoolRetryOutcome.Failure -> {
                PoolDeadReportPolicy.reportTypeFor(outcome.kind)?.let { type ->
                    report(service, id, type) { report(type) }
                }
                null
            }
        }
    }
}
