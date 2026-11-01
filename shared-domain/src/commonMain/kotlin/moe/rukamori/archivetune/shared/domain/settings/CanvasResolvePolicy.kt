package moe.rukamori.archivetune.shared.domain.settings

/**
 * The pieces of canvas resolution that do not depend on a host's HTTP client or decoder: the order the current
 * occurrence's providers are tried in, the stored-choice and connectivity gates each request must pass, the
 * preference values a resolve loop watches, the bounded extra-resolver endpoint list, the https rule a loop URL
 * must satisfy, the audio-to-loop position mapping, and the track-name comparer the Spotify match uses. Both
 * player hosts share this so the same song resolves to a canvas the same way on each.
 */

/** Every provider in the order the player tries them: the declared order of [CanvasHealthProvider]. */
fun canvasResolveProviderOrder(): List<CanvasHealthProvider> = CanvasHealthProvider.entries

/** The provider a source name maps to, or null when it names none. */
fun canvasHealthProviderOf(sourceName: String?): CanvasHealthProvider? =
    CanvasHealthProvider.entries.firstOrNull { it.name == sourceName }

/**
 * Whether a remote canvas request for [provider] may run now: the master toggle, the stored source choice, the
 * Spotify toggle where it applies, and the connectivity rules (online, Wi-Fi-only, low-data and the animated
 * mobile-data rule). This is the same gate the health rows apply, so a request never runs through a provider the
 * settings page shows closed.
 */
fun PreferenceSnapshot.canvasRemoteAllowed(
    provider: CanvasHealthProvider,
    connectivity: CanvasConnectivity,
): Boolean = canvasPlaybackAllowed(provider, connectivity)

/**
 * The preference values a canvas resolve loop watches. A change in any of them re-resolves the current
 * occurrence: the toggles and source choice, the network policy, the extra resolver endpoints and the Spotify
 * catalog identity, which decides whether the Spotify token may still be used.
 */
fun canvasPreferenceProbe(preferences: PreferenceSnapshot?): List<Any?> = preferences?.let {
    listOf(
        it[ArchiveTuneCanvasKey],
        it[SpotifyCanvasKey],
        it[CanvasSourceKey],
        it[CanvasWifiOnlyKey],
        it.lowDataMode(),
        it[DisableAnimationsKey],
        it[CanvasResolverEndpointsKey],
        it[SpotifySpDcKey],
    )
}.orEmpty()

/**
 * The user's extra Spotify canvas resolver endpoints: newline- or comma-separated http/https URLs without
 * userinfo, trimmed of a trailing slash, deduplicated and bounded at eight entries.
 */
fun parseCanvasResolverEndpoints(raw: String?): List<String> = raw.orEmpty().split('\n', ',')
    .map { it.trim().trimEnd('/') }
    .filter(::canvasWebUrlAllowed)
    .distinct()
    .take(MAX_CANVAS_RESOLVER_ENDPOINTS)

/** Only an https URL with a host and no userinfo is handed to the canvas fetch or decode path. */
fun canvasLoopUrlAllowed(value: String): Boolean = canvasUrlHasHost(value, httpsOnly = true)

private fun canvasWebUrlAllowed(value: String): Boolean = canvasUrlHasHost(value, httpsOnly = false)

private fun canvasUrlHasHost(value: String, httpsOnly: Boolean): Boolean {
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = value.substring(0, schemeEnd)
    if (scheme != "https" && (httpsOnly || scheme != "http")) return false
    val authority = value.substring(schemeEnd + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isEmpty() || '@' in authority) return false
    return authority.none { it.isWhitespace() || it < ' ' }
}

/**
 * The position inside the canvas loop for one playback position: the real elapsed audio time modulo the decoded
 * duration, so a seek inside the song stays continuous inside the loop. An unknown duration plays the elapsed
 * time from the start once.
 */
fun canvasLoopPositionMs(audioMs: Long, anchorMs: Long, durationMs: Long?): Long {
    val elapsed = audioMs.coerceAtLeast(0) - anchorMs.coerceAtLeast(0)
    if (durationMs == null || durationMs <= 0L) return elapsed.coerceAtLeast(0L)
    val remainder = elapsed % durationMs
    return if (remainder < 0L) remainder + durationMs else remainder
}

/**
 * The letter/digit fold the Spotify track match and the top-mixes recommender use: equal when the folded forms
 * are equal or one, longer than three characters, contains the other. Two blank folds never match.
 */
fun comparableSongTitle(left: String, right: String): Boolean {
    fun fold(value: String): String = value.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")
    val a = fold(left)
    val b = fold(right)
    return a.isNotBlank() && b.isNotBlank() &&
        (a == b || (a.length > 3 && b.contains(a)) || (b.length > 3 && a.contains(b)))
}

private const val MAX_CANVAS_RESOLVER_ENDPOINTS = 8
