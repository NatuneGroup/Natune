/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori; © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.home

enum class HomeSource { YOUTUBE, SPOTIFY, QQ }

/** Availability comes from the account owner. Never expose credential strings to the screen. */
fun resolvedHomeSources(stored: String, spotifyAvailable: Boolean, qqAvailable: Boolean): List<HomeSource> {
    val parsed = stored.split(',').mapNotNull { name ->
        HomeSource.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }.distinct()
    val candidates = parsed.ifEmpty { HomeSource.entries }
    return candidates.filter {
        when (it) {
            HomeSource.YOUTUBE -> true
            HomeSource.SPOTIFY -> spotifyAvailable
            HomeSource.QQ -> qqAvailable
        }
    }.ifEmpty { listOf(HomeSource.YOUTUBE) }
}

fun resolvedHomeSource(stored: String?, available: List<HomeSource>): HomeSource =
    available.firstOrNull { it.name == stored } ?: available.firstOrNull() ?: HomeSource.YOUTUBE
