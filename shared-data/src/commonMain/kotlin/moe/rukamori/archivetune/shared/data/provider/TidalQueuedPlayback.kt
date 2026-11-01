package moe.rukamori.archivetune.shared.data.provider

/**
 * Pure rules for the hifi-api 202 queued-playback protocol: how long to wait before the next poll,
 * and which status or cancel URL may be contacted. The HTTP loop stays in [TidalInstanceResolver],
 * which owns the clients, the clock and the per-instance cooldowns.
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
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

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
    private fun httpDateEpochMs(value: String): Long? {
        val parts = value.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 6 || !parts[0].endsWith(",") || parts[5] != "GMT") return null
        val day = parts[1].toIntOrNull() ?: return null
        val month = MONTHS.indexOf(parts[2]) + 1
        if (month == 0) return null
        val year = parts[3].toIntOrNull() ?: return null
        val clock = parts[4].split(':')
        if (clock.size != 3) return null
        val hour = clock[0].toIntOrNull() ?: return null
        val minute = clock[1].toIntOrNull() ?: return null
        val second = clock[2].toIntOrNull() ?: return null
        if (day !in 1..31 || year < 1970 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
        val days = daysFromCivil(year, month, day)
        return (days * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
    }

    /** Days since 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's civil-days algorithm). */
    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val shiftedYear = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
        val yearOfEra = shiftedYear - era * 400
        val monthIndex = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
