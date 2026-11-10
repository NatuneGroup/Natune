package moe.rukamori.archivetune.auth

/** Timing and failure rules for keeping a device-code YouTube sign-in alive across restarts. */
internal object YouTubeOAuthRefreshPolicy {
    /** Refresh this far before actual expiry, so a request never races the deadline. */
    const val REFRESH_SKEW_MS = 5 * 60 * 1000L

    /** Pause before trying again after a refresh that did not get through (offline, server error). */
    const val RETRY_DELAY_MS = 60 * 1000L

    fun needsRefresh(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs - REFRESH_SKEW_MS <= nowMs

    fun delayUntilRefreshMs(expiresAtMs: Long, nowMs: Long): Long = (expiresAtMs - REFRESH_SKEW_MS - nowMs).coerceAtLeast(0L)

    /**
     * Only Google's `invalid_grant` means the refresh token itself is dead (revoked, password change, idle
     * expiry). A missing reply or any other error leaves the stored sign-in for the next attempt.
     */
    fun isGrantRevoked(error: String?): Boolean = error == "invalid_grant"
}
