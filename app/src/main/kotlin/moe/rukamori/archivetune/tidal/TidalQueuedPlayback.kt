package moe.rukamori.archivetune.tidal

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure rules for the hifi-api 202 queued-playback protocol, mirroring the KMP resolver: how long to
 * wait before the next poll, and which status or cancel URL may be contacted. The OkHttp poll loop
 * stays in [TidalAudioProvider], which owns the client and the attempt deadline.
 */
internal object TidalQueuedPlayback {
    /** Poll cadence when the server gives no usable Retry-After. */
    const val DEFAULT_POLL_INTERVAL_MS = 1_000L

    /** Floor for an honoured delay, so `Retry-After: 0` cannot turn polling into a hot loop. */
    const val MIN_POLL_INTERVAL_MS = 250L

    /** Ceiling for an honoured delay; a longer wait cannot finish inside any attempt budget anyway. */
    const val MAX_POLL_INTERVAL_MS = 60_000L

    /** Bounds any parsed Retry-After so the arithmetic stays far away from Long overflow. */
    private const val MAX_HONOURED_DELAY_MS = 86_400_000L
    private const val PLAYBACK_REQUESTS_PATH = "/playback/requests/"
    private val REQUEST_ID_REGEX = Regex("[A-Za-z0-9_-]{1,256}")
    private val FAILED_STATES = setOf("failed", "error", "cancelled", "canceled", "expired", "rejected")

    /** Delay before the next poll: the server's Retry-After when it is usable, otherwise the default. */
    fun pollDelayMs(
        retryAfter: String?,
        nowMs: Long,
    ): Long =
        (retryAfterDelayMs(retryAfter, nowMs) ?: DEFAULT_POLL_INTERVAL_MS)
            .coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)

    /**
     * Retry-After as a delay measured from [nowMs]. Accepts delta-seconds and IMF-fixdate, the two
     * forms RFC 9110 defines; any other value is treated as absent.
     */
    fun retryAfterDelayMs(
        value: String?,
        nowMs: Long,
    ): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.all { it in '0'..'9' }) {
            val seconds = raw.toLongOrNull() ?: return MAX_HONOURED_DELAY_MS
            return seconds.coerceAtMost(MAX_HONOURED_DELAY_MS / 1_000L) * 1_000L
        }
        val untilMs = httpDateEpochMs(raw) ?: return null
        return (untilMs - nowMs).coerceIn(0L, MAX_HONOURED_DELAY_MS)
    }

    /**
     * The status or cancel URL of a queued request, or null when it does not name a playback request
     * on [baseUrl]. The result is rebuilt from the validated id, so a poll cannot leave the instance.
     */
    fun requestUrl(
        baseUrl: String,
        candidate: String?,
    ): String? {
        val raw = candidate?.trim().orEmpty()
        val path =
            when {
                raw.startsWith("$baseUrl/") -> raw.substring(baseUrl.length)
                raw.startsWith("/") -> raw
                else -> return null
            }
        if (!path.startsWith(PLAYBACK_REQUESTS_PATH)) return null
        val requestId = path.substring(PLAYBACK_REQUESTS_PATH.length)
        return if (REQUEST_ID_REGEX.matches(requestId)) "$baseUrl$PLAYBACK_REQUESTS_PATH$requestId" else null
    }

    /** True when the server reports that a queued request ended unsuccessfully, so polling stops. */
    fun isFailedState(state: String?): Boolean = state != null && state.lowercase() in FAILED_STATES

    /** IMF-fixdate such as `Sun, 06 Nov 1994 08:49:37 GMT`, as epoch milliseconds. */
    private fun httpDateEpochMs(value: String): Long? =
        runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
}
