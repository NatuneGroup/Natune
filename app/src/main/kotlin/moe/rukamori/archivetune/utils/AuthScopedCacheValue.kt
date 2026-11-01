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
     * The rendition class the URL was resolved for; a cached URL is reusable only while its
     * rendition class is the one the next resolution asks for.
     */
    val low: Boolean = false,
    /** The selected format's itag — the rendition identity the player byte-cache keys under. */
    val itag: Int? = null,
) {
    /** True when this URL's stored rendition class is the one a resolution asks for. */
    fun matchesRendition(requestedLow: Boolean): Boolean = low == requestedLow

    /**
     * The ephemeral player-cache key bytes fetched through this URL belong under: the resolved
     * itag scopes the key ("<mediaId>#i<itag>") so two renditions never mix partial spans. A
     * legacy entry without an itag reads and writes the bare mediaId key it always had.
     */
    fun playbackCacheKey(mediaId: String): String = if (itag != null) "$mediaId#i$itag" else mediaId

    fun isValidFor(
        authFingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingMs: Long = 0L,
    ): Boolean = this.authFingerprint == authFingerprint && expiresAtMs > nowMs + minimumRemainingMs
}
