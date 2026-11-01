package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * The live wait of the hifi-api 202 queued-playback protocol. [TidalInstanceResolver] owns the HTTP
 * exchange, the deadline arithmetic, the ticket cancel and the per-instance rate-limit marks; the
 * waiter borrows them, so the resolver keeps no queue-polling code of its own. The poll cadence and
 * the ticket URL contract stay with [TidalQueuedPlayback].
 */
internal class TidalQueuedPlaybackWaiter(
    private val nowMs: () -> Long,
    private val streamRequestTimeoutMs: Long,
    private val get: suspend (url: String, timeoutMs: Long) -> Poll,
    private val cancel: (cancelUrl: String) -> Unit,
    private val onRateLimited: suspend (baseUrl: String, retryAfterMs: Long) -> Unit,
    private val rateLimitCooldownMs: Long,
) {
    /** One poll reply, reduced to the status, body and Retry-After the loop reads. */
    class Poll(
        val status: Int,
        val body: String,
        val retryAfter: String?,
    )

    /**
     * Waits out a 202 ticket and returns the completed playback body. The status URL reports on the
     * queued request and finishes with the payload a direct 200 would carry. Every exit that leaves the
     * ticket open cancels it best-effort, including cancellation by the race, so the account's single
     * in-flight slot is released rather than held until the server expires the record.
     */
    suspend fun await(
        endpoint: TidalInstanceResolver.TidalDownloadEndpoint,
        acceptedBody: String,
        acceptedRetryAfter: String?,
        acceptedLocation: String?,
        deadlineMs: Long,
    ): String {
        val acceptedJson = runCatching { Json.parseToJsonElement(acceptedBody).jsonObjectOrNull }.getOrNull()
        val statusUrl =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("statusUrl"))
                ?: TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedLocation)
        val cancelTarget =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("cancelUrl")) ?: statusUrl
        var settled = false
        try {
            val pollUrl =
                statusUrl ?: throw TidalInstanceResolver.TidalAudioResolutionException(
                    "TIDAL ${endpoint.name} queued playback has no usable status URL",
                )
            var waitMs = TidalQueuedPlayback.pollDelayMs(acceptedRetryAfter, nowMs())
            while (true) {
                // A poll that would land at or after the deadline cannot help, so give up and cancel now.
                if (nowMs() + waitMs >= deadlineMs) throw queuedDeadlineException(endpoint)
                delay(waitMs)
                val remainingMs = deadlineMs - nowMs()
                if (remainingMs <= 0L) throw queuedDeadlineException(endpoint)
                val poll = get(pollUrl, minOf(streamRequestTimeoutMs, remainingMs))
                if (poll.status == 429) {
                    val retryAfterMs = rateLimitRetryAfterMs(poll.retryAfter, poll.body, rateLimitCooldownMs)
                    onRateLimited(endpoint.baseUrl, retryAfterMs)
                    throw TidalInstanceResolver.TidalRateLimitedException(retryAfterMs)
                }
                if (poll.status == 202) {
                    waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, nowMs())
                    continue
                }
                if (poll.status == 404 || poll.status == 410) {
                    // The server has already dropped the record, so a cancel would only add noise.
                    settled = true
                    throw TidalInstanceResolver.TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback expired (HTTP ${poll.status})",
                    )
                }
                if (poll.status !in 200..299) {
                    throw TidalInstanceResolver.TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback HTTP ${poll.status}: ${poll.body.take(180)}",
                    )
                }
                val pollJson = runCatching { Json.parseToJsonElement(poll.body).jsonObjectOrNull }.getOrNull()
                if (pollJson?.obj("data") != null) {
                    settled = true
                    return poll.body
                }
                val state = pollJson?.stringOrNull("status")
                if (TidalQueuedPlayback.isFailedState(state)) {
                    settled = true
                    throw TidalInstanceResolver.TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback ended: $state")
                }
                waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, nowMs())
            }
        } finally {
            if (!settled && cancelTarget != null) cancel(cancelTarget)
        }
    }

    private fun queuedDeadlineException(
        endpoint: TidalInstanceResolver.TidalDownloadEndpoint,
    ): TidalInstanceResolver.TidalAudioResolutionException =
        TidalInstanceResolver.TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback did not finish before the attempt deadline")
}

/** Header seconds, else the body's retry_after, else [fallbackMs]; shared by direct and polled 429s. */
internal fun rateLimitRetryAfterMs(
    retryAfter: String?,
    body: String,
    fallbackMs: Long,
): Long {
    val headerRetryAfterMs = retryAfter?.toLongOrNull()?.times(1000L)?.coerceAtLeast(60_000L)
    val bodyRetryAfterMs =
        runCatching {
            Json.parseToJsonElement(body).jsonObjectOrNull
                ?.long("retry_after")
                ?.takeIf { it > 0L }
                ?.times(1000L)
        }.getOrNull()
    return headerRetryAfterMs ?: bodyRetryAfterMs ?: fallbackMs
}
