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
 * 400, 401, 403, 404 or 429 are definitive and stop the walk. POST /api/report is different again:
 * a transport failure means the report may already have reached the server, so only an explicit
 * 402 or 5xx answer advances it — a report is never delivered twice.
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
     * definitive. For reports the same statuses are safe failover answers: an explicit 402 or 5xx
     * proves the request was never processed, so re-sending it cannot deliver twice.
     */
    fun isFailoverStatus(status: Int): Boolean = status == 402 || status >= 500

    /**
     * Runs [call] against each candidate endpoint until one answers definitively; the remembered
     * deployment leads the walk while fresh. [call] answers the (status, body) pair or null on a
     * transport failure. A failover status never reaches [remember]. The last answer is returned
     * when every endpoint fails; the caller keeps distinguishing "unreachable" (null) from "the
     * last host said no" (a status). For [report] a null stops the walk: the request may already
     * have reached the server, and only an explicit 402 or 5xx proves it did not.
     */
    suspend fun acrossEndpoints(
        configured: String?,
        report: Boolean = false,
        call: suspend (baseUrl: String) -> Pair<Int, String>?,
    ): Pair<Int, String>? {
        var last: Pair<Int, String>? = null
        for (endpoint in candidates(configured)) {
            val result = call(endpoint)
            if (result == null) {
                if (report) break
                continue
            }
            if (!isFailoverStatus(result.first)) {
                remember(endpoint)
                return result
            }
            last = result
        }
        return last
    }
}
