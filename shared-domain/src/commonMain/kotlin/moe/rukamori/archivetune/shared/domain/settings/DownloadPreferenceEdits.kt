/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

/** Settings wire identities, not a second downloader/provider model. Spotify is not a download source. */
val DEFAULT_DOWNLOAD_PREFERENCE_ORDER = listOf("QOBUZ", "QOBUZ_BACKUP", "TIDAL", "DEEZER", "JIOSAAVN", "YOUTUBE_MUSIC")

fun PreferenceSnapshot.downloadSourcePreferenceOrder(): List<String> {
    val known = DEFAULT_DOWNLOAD_PREFERENCE_ORDER + "AUTO"
    val stored = this[DownloadSourceOrderKey].orEmpty().split(',').map { it.trim().uppercase() }
        .filter { it in known }.distinct()
    if (stored.isEmpty()) return DEFAULT_DOWNLOAD_PREFERENCE_ORDER
    val missing = DEFAULT_DOWNLOAD_PREFERENCE_ORDER.filterNot { it in stored }
    val terminal = stored.indexOf("YOUTUBE_MUSIC")
    return if (terminal < 0) stored + missing else stored.take(terminal) + missing + stored.drop(terminal)
}

fun PreferenceEditor.setDownloadSourcePreferenceOrder(order: List<String>) {
    require(order.distinct().size == order.size && order.containsAll(DEFAULT_DOWNLOAD_PREFERENCE_ORDER) &&
        order.all { it in DEFAULT_DOWNLOAD_PREFERENCE_ORDER || it == "AUTO" })
    this[DownloadSourceOrderKey] = order.joinToString(",")
    // Keep the shipped old single-selection key untouched for old backups/readers.
}
