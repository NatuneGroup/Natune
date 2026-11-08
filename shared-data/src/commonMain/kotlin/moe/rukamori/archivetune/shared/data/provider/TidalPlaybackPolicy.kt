package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** TIDAL uses HTTP 401 for subscription ceilings as well as invalid credentials. */
enum class TidalFailureKind {
    QUALITY_RESTRICTED, AUTH_REJECTED, ENTITLEMENT_LOST, RATE_LIMITED, CONTENT_UNAVAILABLE, TRANSIENT,
}

/** Carries safe diagnostics only; response bodies can contain account data and are never retained. */
class TidalPlaybackFailure(
    val status: Int,
    val subStatus: Int?,
    val kind: TidalFailureKind,
    val retryAfterMs: Long? = null,
) : Exception("TIDAL HTTP $status substatus=${subStatus ?: "unknown"} kind=$kind")

fun classifyTidalFailure(status: Int, subStatus: Int?, message: String?): TidalFailureKind {
    val detail = message.orEmpty().lowercase()
    return when {
        status == 429 -> TidalFailureKind.RATE_LIMITED
        // Observed on valid FREE sessions: 401/5003, not an expired or revoked token.
        subStatus == 5003 || (detail.contains("quality") &&
            (detail.contains("not allowed") || detail.contains("not available")) &&
            detail.contains("subscription")) -> TidalFailureKind.QUALITY_RESTRICTED
        status == 402 || detail.contains("user is not premium") || detail.contains("user not premium") ||
            detail.contains("not_premium") || detail.contains("does not have premium") ||
            detail.contains("no active subscription") || detail.contains("subscription expired") ->
            TidalFailureKind.ENTITLEMENT_LOST
        status == 401 || status == 403 -> TidalFailureKind.AUTH_REJECTED
        status == 404 -> TidalFailureKind.CONTENT_UNAVAILABLE
        else -> TidalFailureKind.TRANSIENT
    }
}

fun TidalFailureKind.toPoolFailureKind(): PoolFailureKind = when (this) {
    TidalFailureKind.QUALITY_RESTRICTED, TidalFailureKind.CONTENT_UNAVAILABLE -> PoolFailureKind.CONTENT_UNAVAILABLE
    TidalFailureKind.AUTH_REJECTED -> PoolFailureKind.AUTH_REJECTED
    TidalFailureKind.ENTITLEMENT_LOST -> PoolFailureKind.ENTITLEMENT_LOST
    TidalFailureKind.RATE_LIMITED -> PoolFailureKind.RATE_LIMITED
    TidalFailureKind.TRANSIENT -> PoolFailureKind.TRANSIENT
}

/** Never raises the user's ceiling, retries a token/rate failure, or requests free preview quality. */
fun tidalQualityAttempts(requested: String): List<String> = when (requested.uppercase()) {
    "HI_RES_LOSSLESS" -> listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH")
    "LOSSLESS" -> listOf("LOSSLESS", "HIGH")
    else -> listOf(requested.uppercase())
}

/** One search result, a bounded walk down quality tiers, and the caller's existing deadline/cancellation. */
suspend fun <T> resolveTidalQuality(
    requested: String,
    onDowngrade: (from: String, to: String) -> Unit = { _, _ -> },
    resolve: suspend (quality: String) -> T?,
): T? {
    val qualities = tidalQualityAttempts(requested)
    for ((index, quality) in qualities.withIndex()) {
        try {
            return resolve(quality)
        } catch (error: TidalPlaybackFailure) {
            if (error.kind != TidalFailureKind.QUALITY_RESTRICTED || index == qualities.lastIndex) throw error
            onDowngrade(quality, qualities[index + 1])
        }
    }
    return null
}

/** A rate limit suppresses new requests, including when every pooled account is limited. */
fun tidalRateLimitCooldownMs(retryAfterMs: Long?): Long =
    (retryAfterMs ?: 60_000L).coerceIn(1_000L, 10 * 60_000L)

class TidalRateLimitGate(private val nowMs: () -> Long) {
    private val lock = Mutex()
    private val limitedUntil = mutableMapOf<String, Long>()

    suspend fun limit(account: String, retryAfterMs: Long?) = lock.withLock {
        val now = nowMs()
        limitedUntil.entries.removeAll { it.value <= now }
        limitedUntil[account] = maxOf(limitedUntil[account] ?: 0L, now + tidalRateLimitCooldownMs(retryAfterMs))
    }

    suspend fun canAttempt(account: String): Boolean = lock.withLock {
        val until = limitedUntil[account] ?: return@withLock true
        if (until > nowMs()) return@withLock false
        limitedUntil.remove(account)
        true
    }
}

/** Keep a full lossy answer while checking the remaining accounts for requested lossless audio. */
suspend fun <A, T : Any> resolveTidalPoolAccounts(
    accounts: List<A>,
    preferLossless: Boolean,
    canAttempt: suspend (A) -> Boolean,
    isLossless: (T) -> Boolean,
    resolve: suspend (A) -> T?,
): T? {
    var fallback: T? = null
    for (account in accounts) {
        if (!canAttempt(account)) continue
        val result = resolve(account) ?: continue
        if (!preferLossless || isLossless(result)) return result
        if (fallback == null) fallback = result
    }
    return fallback
}
