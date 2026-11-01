package moe.rukamori.archivetune.shared.domain.source

import kotlin.time.Instant

/** Only absolute authorization windows are understood here; a URL with an unknown TTL stays unknown. */
object StreamUrlExpiry {
    const val GUARD_MS = 30_000L

    fun fromUrl(url: String): Long? {
        val parameters = url.substringBefore('#').substringAfter('?', "")
            .split('&').filter { it.isNotEmpty() }
            .groupBy({ it.substringBefore('=').lowercase() }, { decode(it.substringAfter('=', "")) })
        if ("x-amz-date" in parameters || "x-amz-expires" in parameters) {
            val date = parameters["x-amz-date"]?.singleOrNull() ?: return null
            val seconds = parameters["x-amz-expires"]?.singleOrNull()?.toLongOrNull() ?: return null
            if (seconds !in 0L..7L * 24 * 60 * 60) return null
            val issuedAt = amzDate(date) ?: return null
            return issuedAt + seconds * 1_000L
        }
        // Qobuz's signed CDN URLs use an absolute Unix `etsp`, not a relative cache lifetime.
        parameters["etsp"]?.let { return epochSeconds(it.singleOrNull()) }
        // Deezer/Akamai and Spotify CDN tokens embed an absolute exp in their signed token.
        for (name in listOf("hdnts", "hdntl", "__token__")) {
            val tokens = parameters[name] ?: continue
            val token = tokens.singleOrNull() ?: return null
            val expires = token.split('~').filter { it.substringBefore('=') == "exp" }
            return epochSeconds(expires.singleOrNull()?.substringAfter('='))
        }
        if ("signature" in parameters && "key-pair-id" in parameters) {
            return epochSeconds(parameters["expires"]?.singleOrNull())
        }
        return null
    }

    /** A cache may shorten a known window, never stretch it (including a window already elapsed). */
    fun cacheDeadline(nowMs: Long, ttlMs: Long, expiresAtEpochMs: Long?, guardMs: Long = GUARD_MS): Long {
        require(ttlMs >= 0L && guardMs >= 0L)
        val ttlEnd = if (nowMs > Long.MAX_VALUE - ttlMs) Long.MAX_VALUE else nowMs + ttlMs
        val signedEnd = expiresAtEpochMs?.let {
            if (it < Long.MIN_VALUE + guardMs) Long.MIN_VALUE else it - guardMs
        }
        return signedEnd?.let { minOf(ttlEnd, it) } ?: ttlEnd
    }

    private fun epochSeconds(value: String?): Long? =
        value?.toLongOrNull()?.takeIf { it in 0L..Long.MAX_VALUE / 1_000L }?.times(1_000L)

    private fun amzDate(value: String): Long? {
        if (value.length != 16 || value[8] != 'T' || value[15] != 'Z') return null
        val iso = "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}" +
            "T${value.substring(9, 11)}:${value.substring(11, 13)}:${value.substring(13, 15)}Z"
        return runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
    }

    /** Signature timestamps/tokens are ASCII; unsupported escapes remain unreadable rather than guessed. */
    private fun decode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null && byte in 0..127) {
                    append(byte.toChar())
                    index += 3
                    continue
                }
            }
            append(value[index++])
        }
    }
}
