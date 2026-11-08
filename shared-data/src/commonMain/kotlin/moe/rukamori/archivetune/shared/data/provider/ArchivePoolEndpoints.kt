package moe.rukamori.archivetune.shared.data.provider

import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The ordered ArchivePool deployments one pool request is tried against. A build names its own
 * deployment (the Cloudflare Worker) and that is the whole order: the retired
 * `archivepool.vercel.app` fallback answered `402 DEPLOYMENT_DISABLED` on every call, so walking
 * to it only ever bought a second request per primary 5xx. A deployment that produced a
 * definitive API answer is remembered for about [REMEMBER] so the next request uses it directly.
 *
 * A request moves to the next endpoint on a transport failure — connection refused, DNS failure,
 * timeout — or a host-level answer ([isFailoverStatus]: HTTP 402, or a 5xx other than 503). Every
 * API-level answer is definitive and stops the walk: 400/401/403/404, and also 429 and 503,
 * which are the server's own refusals. 429 and 503 carry `Retry-After`; the caller backs off for
 * that window instead of re-asking through a host that already answered
 * ([isRefusal]). POST /api/report is different again: a transport failure means the report may
 * already have reached the server, so only an explicit 402 or failover 5xx answer advances it — a
 * report is never delivered twice.
 */
object ArchivePoolEndpoints {

    /**
     * The public deployment a build falls back to when nothing is configured. It is not appended
     * to a configured deployment's order any more: the Vercel host is disabled (402) and is only
     * reached when a build names no deployment at all.
     */
    const val FALLBACK_BASE_URL = "https://archivepool.vercel.app"

    /** How long the deployment that last produced a definitive answer is tried first. */
    private val REMEMBER = 10.minutes

    /** Internal so tests can backdate or clear the memory; production writes go through [remember]. */
    @Volatile
    internal var lastGood: Pair<String, TimeSource.Monotonic.ValueTimeMark>? = null

    /**
     * The configured deployment normalized: trimmed, without a trailing slash, https only. A blank
     * or non-https value answers the public fallback, matching every host's unset-means-default rule.
     */
    fun primary(configured: String?): String =
        configured?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: FALLBACK_BASE_URL

    /**
     * The deduplicated endpoint order for one request: the remembered deployment first while it is
     * still fresh, then the configured primary.
     */
    fun candidates(configured: String?): List<String> {
        val ordered = LinkedHashSet<String>(2)
        lastGood?.takeIf { it.second.elapsedNow() < REMEMBER }?.let { ordered += it.first }
        ordered += primary(configured)
        return ordered.toList()
    }

    /**
     * Remembers the deployment that produced a definitive answer, so the next request tries it
     * first. Only the answering host is stored; failover statuses and refusals never reach this.
     */
    fun remember(baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) return
        lastGood = normalized to TimeSource.Monotonic.markNow()
    }

    /**
     * True when [status] means the deployment itself failed rather than the API: 402 (the host is
     * down for billing) or a 5xx other than 503. 503 is the pool's own "temporarily unavailable"
     * answer with `Retry-After`, so it is definitive for a GET — walking to another deployment
     * would only repeat the same refusal through a dead host. For reports the failover statuses are
     * still safe: an explicit 402 or failover 5xx proves the request was never processed, so
     * re-sending it cannot deliver twice.
     */
    fun isFailoverStatus(status: Int): Boolean = status == 402 || (status >= 500 && status != 503)

    /**
     * True when [status] is the pool refusing for a reason the caller must back off from:
     * unauthorized (401/403) and throttled/unavailable (429/503).
     */
    fun isRefusal(status: Int): Boolean = status == 401 || status == 403 || status == 429 || status == 503

    /**
     * Runs [call] against each candidate endpoint until one answers definitively; the remembered
     * deployment leads the walk while fresh. [call] answers the reply or null on a transport
     * failure. A failover status never reaches [remember], and a refusal is not remembered either.
     * The last answer is returned when every endpoint fails; the caller keeps distinguishing
     * "unreachable" (null) from "the last host said no" (a status). For [report] a null stops the
     * walk: the request may already have reached the server, and only an explicit 402 or failover
     * 5xx proves it did not.
     */
    suspend fun acrossEndpoints(
        configured: String?,
        report: Boolean = false,
        call: suspend (baseUrl: String) -> PoolResponse?,
    ): PoolResponse? {
        var last: PoolResponse? = null
        for (endpoint in candidates(configured)) {
            val result = call(endpoint)
            if (result == null) {
                if (report) break
                continue
            }
            if (!isFailoverStatus(result.status)) {
                if (!isRefusal(result.status)) remember(endpoint)
                return result
            }
            last = result
        }
        return last
    }
}
