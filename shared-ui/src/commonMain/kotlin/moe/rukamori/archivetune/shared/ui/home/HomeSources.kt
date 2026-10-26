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
