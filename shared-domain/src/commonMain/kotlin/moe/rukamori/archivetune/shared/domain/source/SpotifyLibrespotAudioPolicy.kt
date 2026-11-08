package moe.rukamori.archivetune.shared.domain.source

/** Wire values from Spotify's audio_file.Format; Media3 decodes the selected Vorbis file. */
object SpotifyLibrespotAudioPolicy {
    const val SCHEME = "librespot-spotify"
    const val VORBIS_96 = 0
    const val VORBIS_160 = 1
    const val VORBIS_320 = 2

    private val trackIdPattern = Regex("^[0-9A-Za-z]{22}$")
    private val trackUrlPattern = Regex(
        "^https://open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/([0-9A-Za-z]{22})(?:[?#].*)?$",
    )
    private val isrcPattern = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

    /** Accept only track identities; an album/episode or another host must never become a track. */
    fun trackId(value: String?): String? {
        val candidate = value?.trim() ?: return null
        val id = when {
            candidate.startsWith("spotify:track:") -> candidate.removePrefix("spotify:track:")
            candidate.startsWith("sp:") -> candidate.removePrefix("sp:")
            candidate.startsWith("https://") -> trackUrlPattern.matchEntire(candidate)?.groupValues?.get(1)
            else -> candidate
        }
        return id?.takeIf(trackIdPattern::matches)
    }

    fun trackUri(value: String?): String? = trackId(value)?.let { "spotify:track:$it" }

    fun isrc(value: String?): String? = value?.trim()?.replace("-", "")?.uppercase()?.takeIf(isrcPattern::matches)

    /** A capped quality never silently selects a higher-only file. AUTO uses the best available. */
    fun preferredFormats(quality: String?, lowData: Boolean): List<Int> = when {
        lowData || quality == "LOW" -> listOf(VORBIS_96)
        quality == "HIGH" -> listOf(VORBIS_160, VORBIS_96)
        else -> listOf(VORBIS_320, VORBIS_160, VORBIS_96)
    }

    fun selectFormat(available: Set<Int>, quality: String?, lowData: Boolean): Int? =
        preferredFormats(quality, lowData).firstOrNull(available::contains)

    fun cacheNamespace(accountFingerprint: String, quality: String?, lowData: Boolean): String =
        "LIBRESPOT:$accountFingerprint:${preferredFormats(quality, lowData).joinToString(",")}"

    /** Cache bytes are usable only for this account, requested ceiling and actual audio file. */
    fun cacheAdmitted(tag: String?, accountFingerprint: String, quality: String?, lowData: Boolean): Boolean {
        val prefix = "${cacheNamespace(accountFingerprint, quality, lowData)}:"
        if (tag == null || !tag.startsWith(prefix)) return false
        val rendition = tag.removePrefix(prefix).split(':')
        return rendition.size == 2 && rendition[0].toIntOrNull() in preferredFormats(quality, lowData) &&
            rendition[1].matches(Regex("[0-9a-f]{40}"))
    }

    fun bitrate(format: Int): Int? = when (format) {
        VORBIS_96 -> 96_000
        VORBIS_160 -> 160_000
        VORBIS_320 -> 320_000
        else -> null
    }

    fun admitted(engine: SpotifyStreamEngine, enabled: Boolean, product: SpotifyProduct, grantPresent: Boolean): Boolean =
        engine == SpotifyStreamEngine.LIBRESPOT && enabled && SpotifyPremiumPolicy.librespotAllowed(product) && grantPresent

    /** Key denial and throttling must fall through without rebuilding/retrying the same request. */
    fun failure(code: String?): SpotifyLibrespotFailure = when (code) {
        "AUTH" -> SpotifyLibrespotFailure.AUTHENTICATION
        "ACCOUNT_CHANGED" -> SpotifyLibrespotFailure.ACCOUNT_CHANGED
        "NOT_PREMIUM" -> SpotifyLibrespotFailure.NOT_PREMIUM
        "TRACK_UNAVAILABLE", "ACCESS_DENIED" -> SpotifyLibrespotFailure.TRACK_UNAVAILABLE
        "UNSUPPORTED_FORMAT" -> SpotifyLibrespotFailure.UNSUPPORTED_FORMAT
        "AUDIO_KEY" -> SpotifyLibrespotFailure.AUDIO_KEY_DENIED
        "RATE_LIMITED" -> SpotifyLibrespotFailure.RATE_LIMITED
        "TIMEOUT" -> SpotifyLibrespotFailure.TIMEOUT
        "NETWORK" -> SpotifyLibrespotFailure.NETWORK
        "NATIVE_UNAVAILABLE" -> SpotifyLibrespotFailure.NATIVE_UNAVAILABLE
        else -> SpotifyLibrespotFailure.INVALID_RESPONSE
    }
}

/** Cancellation is propagated separately. Every provider failure lets the existing source chain continue. */
enum class SpotifyLibrespotFailure(val reconnectOnNextRequest: Boolean = false) {
    AUTHENTICATION(true),
    ACCOUNT_CHANGED,
    NOT_PREMIUM,
    TRACK_UNAVAILABLE,
    UNSUPPORTED_FORMAT,
    AUDIO_KEY_DENIED,
    RATE_LIMITED,
    TIMEOUT(true),
    NETWORK(true),
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
}
