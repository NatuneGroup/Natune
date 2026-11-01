package moe.rukamori.archivetune.tidal

import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The live wait of the hifi-api 202 queued-playback protocol on the calling thread. [TidalAudioProvider]
 * owns the OkHttp client, the user agent, the fallback cooldown and the per-instance rate-limit marks;
 * the waiter borrows them so the provider keeps no queue-polling code of its own. The poll cadence and
 * the ticket URL contract stay with [TidalQueuedPlayback].
 */
internal class TidalQueuedPlaybackWaiter(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val rateLimitCooldownMs: Long,
    private val markRateLimited: (baseUrl: String, retryAfterMs: Long) -> Unit,
) {
    /**
     * Waits out a hifi-api 202 ticket on the calling thread and returns the completed playback body.
     * It runs inside the endpoint's runInterruptible, so an abandoned attempt interrupts the wait. Every
     * exit that leaves the ticket open cancels it best-effort, so the account's single in-flight slot is
     * released rather than held until the server expires the record.
     */
    fun awaitQueuedPlayback(
        endpoint: TidalAudioProvider.TidalDownloadEndpoint,
        acceptedBody: String,
        acceptedRetryAfter: String?,
        acceptedLocation: String?,
    ): String {
        val acceptedJson = runCatching { JSONObject(acceptedBody) }.getOrNull()
        val statusUrl =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("statusUrl"))
                ?: TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedLocation)
        val cancelTarget =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("cancelUrl")) ?: statusUrl
        val budgetMs =
            AudioSourceAttemptScope.current()?.remainingMillis()
                ?: AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        Timber.tag("TidalAudio").d("%s queued the playback request; polling its status", endpoint.name)
        var settled = false
        try {
            val pollUrl =
                statusUrl ?: throw TidalAudioProvider.TidalAudioResolutionException(
                    "TIDAL ${endpoint.name} queued playback has no usable status URL",
                )
            var waitMs = TidalQueuedPlayback.pollDelayMs(acceptedRetryAfter, System.currentTimeMillis())
            while (true) {
                // A poll that would land at or after the deadline cannot help, so give up and cancel now.
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (waitMs >= remainingMs) throw queuedDeadlineException(endpoint)
                Thread.sleep(waitMs)
                val poll = pollQueuedPlayback(pollUrl)
                if (poll.code == 429) {
                    val retryAfterMs = rateLimitRetryAfterMs(poll.retryAfter, poll.body, rateLimitCooldownMs)
                    markRateLimited(endpoint.baseUrl, retryAfterMs)
                    throw TidalAudioProvider.TidalRateLimitedException(retryAfterMs)
                }
                if (poll.code == 202) {
                    waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, System.currentTimeMillis())
                    continue
                }
                if (poll.code == 404 || poll.code == 410) {
                    // The server has already dropped the record, so a cancel would only add noise.
                    settled = true
                    throw TidalAudioProvider.TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback expired (HTTP ${poll.code})",
                    )
                }
                if (poll.code !in 200..299) {
                    throw TidalAudioProvider.TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback HTTP ${poll.code}: ${poll.body.take(180)}",
                    )
                }
                val pollJson = runCatching { JSONObject(poll.body) }.getOrNull()
                if (pollJson?.optJSONObject("data") != null) {
                    settled = true
                    return poll.body
                }
                val state = pollJson?.stringOrNull("status")
                if (TidalQueuedPlayback.isFailedState(state)) {
                    settled = true
                    throw TidalAudioProvider.TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback ended: $state")
                }
                waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, System.currentTimeMillis())
            }
        } finally {
            if (!settled && cancelTarget != null) cancelQueuedPlayback(cancelTarget)
        }
    }

    private class QueuedPollReply(
        val code: Int,
        val body: String,
        val retryAfter: String?,
    )

    private fun pollQueuedPlayback(pollUrl: String): QueuedPollReply {
        val request =
            Request
                .Builder()
                .url(pollUrl)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .build()
        return client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            QueuedPollReply(
                code = response.code,
                body = response.body.string(),
                retryAfter = response.header("Retry-After"),
            )
        }
    }

    private fun queuedDeadlineException(
        endpoint: TidalAudioProvider.TidalDownloadEndpoint,
    ): TidalAudioProvider.TidalAudioResolutionException =
        TidalAudioProvider.TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback did not finish before the attempt deadline")

    /**
     * Fire-and-forget on a daemon thread. The attempt is already over and must not wait for the cancel,
     * and a failed cancel only leaves the record to expire on the server.
     */
    private fun cancelQueuedPlayback(cancelUrl: String) {
        Thread {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(cancelUrl)
                        .delete()
                        .header("Accept", "application/json")
                        .header("User-Agent", userAgent)
                        .build()
                client
                    .newBuilder()
                    .callTimeout(QUEUED_CANCEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .close()
            }
        }.apply {
            name = "tidal-queued-cancel"
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val QUEUED_CANCEL_TIMEOUT_MS = 5_000L
    }
}

/** Header seconds, else the body's retry_after, else [fallbackMs]; shared by direct and polled 429s. */
internal fun rateLimitRetryAfterMs(
    retryAfterHeader: String?,
    responseBody: String,
    fallbackMs: Long,
): Long {
    val headerRetryAfterMs = retryAfterHeader?.toLongOrNull()?.times(1000L)?.coerceAtLeast(60_000L)
    val bodyRetryAfterMs =
        runCatching {
            JSONObject(responseBody)
                .optLong("retry_after")
                .takeIf { it > 0L }
                ?.times(1000L)
        }.getOrNull()
    return headerRetryAfterMs ?: bodyRetryAfterMs ?: fallbackMs
}

internal fun JSONObject.stringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

internal fun JSONObject.longOrNull(key: String): Long? =
    if (has(key)) optLong(key).takeIf { it > 0L } else null

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key)) optDouble(key).takeIf { it > 0.0 && !it.isNaN() } else null
