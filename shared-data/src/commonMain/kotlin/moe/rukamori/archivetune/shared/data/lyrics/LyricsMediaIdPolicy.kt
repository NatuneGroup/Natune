package moe.rukamori.archivetune.shared.data.lyrics

/**
 * Which providers may be asked about which media ids. Ported from the Android `supportsMediaId`.
 *
 * Telegram and local ids are not YouTube ids, so YouTube-keyed providers would only waste a
 * request on them. The Android app-only YouTube providers are not in this module; in this module
 * [SimpMusicLyricsProvider] is the one YouTube-keyed provider that the rule skips.
 */
object LyricsMediaIdPolicy {
    /** URI schemes that Android's `isLocalMediaId` accepts, compared case-insensitively. */
    private val LOCAL_SCHEMES = setOf("content", "file", "android.resource")
    private val SCHEME_REGEX = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")
    private const val TELEGRAM_PREFIX = "telegram://track/"

    fun isTelegramMediaId(mediaId: String): Boolean {
        if (!mediaId.startsWith(TELEGRAM_PREFIX)) return false
        // Same decode rule as the app's TelegramMediaId: chat, message and file id must be numeric.
        val segments = mediaId.removePrefix(TELEGRAM_PREFIX).split('/')
        if (segments.size < 3) return false
        return segments[0].toLongOrNull() != null &&
            segments[1].toLongOrNull() != null &&
            segments[2].toIntOrNull() != null
    }

    /**
     * Scheme-based instead of `Uri.parse`, so it works in common code. A bare YouTube id has no
     * scheme and is not local.
     */
    fun isLocalMediaId(mediaId: String): Boolean {
        val scheme = SCHEME_REGEX.find(mediaId.trim())?.groupValues?.get(1) ?: return false
        return scheme.lowercase() in LOCAL_SCHEMES
    }

    fun isNonYouTubeMediaId(mediaId: String): Boolean = isTelegramMediaId(mediaId) || isLocalMediaId(mediaId)

    fun supportsMediaId(
        provider: LyricsProvider,
        mediaId: String,
    ): Boolean {
        if (!isNonYouTubeMediaId(mediaId)) return true
        return provider !is SimpMusicLyricsProvider
    }
}
