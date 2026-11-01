package moe.rukamori.archivetune.shared.data.provider

import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The ordered ArchivePool deployments one pool request is tried against while the service moves off
 * Vercel onto Cloudflare Workers: the build-configured deployment first, then the public
 * archivepool.vercel.app origin as the fallback. A deployment that produced a definitive API answer
 * is remembered for about [REMEMBER] so a dead primary is not paid for on every call.
 *
 * A request moves to the next endpoint on a transport failure — connection refused, DNS failure,
 * timeout — or a host-level answer ([isFailoverStatus]: HTTP 402 or 5xx). API-level answers like
 * 400, 401, 403, 404 or 429 are definitive and stop the walk. POST /api/report additionally never
 * retries a definitive answer, so a report is never delivered twice.
 */
object ArchivePoolEndpoints {

    /** The deployment every build falls back to when nothing better is configured or reachable. */
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
     * still fresh, then the configured primary, then the public fallback.
     */
    fun candidates(configured: String?): List<String> {
        val ordered = LinkedHashSet<String>(3)
        lastGood?.takeIf { it.second.elapsedNow() < REMEMBER }?.let { ordered += it.first }
        ordered += primary(configured)
        ordered += FALLBACK_BASE_URL
        return ordered.toList()
    }

    /**
     * Remembers the deployment that produced a definitive answer, so the next request tries it
     * first. Only the answering host is stored; failover statuses never reach this.
     */
    fun remember(baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) return
        lastGood = normalized to TimeSource.Monotonic.markNow()
    }

    /**
     * True when [status] means the deployment itself failed rather than the API: 402 (the host is
     * down for billing) or any 5xx. API-level answers — 400/401/403/404/429 among them — are
     * definitive. [report] counts only 5xx as failover — a 2xx/4xx answer already means the report
     * was seen, and re-sending would risk delivering it twice.
     */
    fun isFailoverStatus(status: Int, report: Boolean = false): Boolean =
        if (report) status >= 500 else status == 402 || status >= 500

    /**
     * Runs [call] against each candidate endpoint until one answers definitively; the remembered
     * deployment leads the walk while fresh. [call] answers the (status, body) pair or null on a
     * transport failure. The last answer is returned when every endpoint fails; the caller keeps
     * distinguishing "unreachable" (null) from "the last host said no" (a status).
     */
    suspend fun acrossEndpoints(
        configured: String?,
        report: Boolean = false,
        call: suspend (baseUrl: String) -> Pair<Int, String>?,
    ): Pair<Int, String>? {
        var last: Pair<Int, String>? = null
        for (endpoint in candidates(configured)) {
            val result = call(endpoint)
            if (result != null && !isFailoverStatus(result.first, report)) {
                remember(endpoint)
                return result
            }
            if (result != null) last = result
        }
        return last
    }
}
