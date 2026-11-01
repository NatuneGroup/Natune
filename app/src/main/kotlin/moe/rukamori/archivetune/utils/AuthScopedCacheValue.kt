/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

data class AuthScopedCacheValue(
    val url: String,
    val expiresAtMs: Long,
    val authFingerprint: String,
    /**
     * The rendition the URL was resolved for. The ephemeral player cache keys renditions
     * separately ("<mediaId>#low"), and a cached URL is reusable only while its rendition is
     * the one the next resolution asks for.
     */
    val low: Boolean = false,
) {
    /** True when this URL's stored rendition is the one a resolution asks for. */
    fun matchesRendition(requestedLow: Boolean): Boolean = low == requestedLow

    fun isValidFor(
        authFingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingMs: Long = 0L,
    ): Boolean = this.authFingerprint == authFingerprint && expiresAtMs > nowMs + minimumRemainingMs
}
