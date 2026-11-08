package moe.rukamori.archivetune.shared.data.provider

/**
 * One ArchivePool HTTP answer with the two headers the client acts on: `Retry-After` carries the
 * server's own quiet period on a 429/503, and `ETag` lets the next read revalidate the body it
 * already stored instead of downloading it again. The plain status/body pair every host used
 * before dropped both, which is why a throttled or revalidating client could only guess.
 */
class PoolResponse(
    val status: Int,
    val body: String = "",
    val retryAfterMs: Long? = null,
    val etag: String? = null,
) {
    /** A 2xx body or a 304 that revalidated the caller's stored copy. */
    val usable: Boolean get() = status in 200..299 || status == 304

    /** True when the server answered with the caller's stored copy instead of a body. */
    val notModified: Boolean get() = status == 304

    /** True when this status is the server refusing (key, rate, or deployment), not a host failure. */
    val refused: Boolean get() = ArchivePoolEndpoints.isRefusal(status)

    companion object {
        /** A `Retry-After` beyond this reads as a deployment problem, not a pause worth honouring. */
        const val MAX_RETRY_AFTER_MS = 6L * 60L * 60L * 1000L

        /**
         * `Retry-After` in delta-seconds as millis. The HTTP-date form is not decoded: it is rare
         * for these feeds and a wrong clock read is worse than the caller's own backoff window.
         */
        fun retryAfterMs(header: String?): Long? {
            val seconds = header?.trim()?.toLongOrNull() ?: return null
            if (seconds <= 0L) return null
            return (seconds * 1000L).coerceAtMost(MAX_RETRY_AFTER_MS)
        }
    }
}
