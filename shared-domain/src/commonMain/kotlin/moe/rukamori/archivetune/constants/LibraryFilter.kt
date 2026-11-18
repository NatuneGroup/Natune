
package moe.rukamori.archivetune.constants

/**
 * The Library's sections.
 *
 * Spotify is a SOURCE, not a section: the Library offers one YTM/Spotify selector above these, and
 * every section shows that service's version of itself. See [LibrarySource]. A stored SPOTIFY value
 * for the default-chip preference falls back to the default, which `toEnum` already does for any
 * unknown name.
 */
enum class LibraryFilter {
    SONGS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
    LIBRARY,
}

val DefaultLibraryFilterOrder =
    listOf(
        LibraryFilter.LIBRARY,
        LibraryFilter.PLAYLISTS,
        LibraryFilter.SONGS,
        LibraryFilter.ARTISTS,
        LibraryFilter.ALBUMS,
    )

val DefaultLibraryFilterOrderPreference =
    DefaultLibraryFilterOrder.joinToString(",") { it.name }

fun String.toLibraryFilterOrder(): List<LibraryFilter> {
    val savedOrder =
        split(",")
            .mapNotNull { savedFilter ->
                DefaultLibraryFilterOrder.firstOrNull { it.name == savedFilter.trim() }
            }.distinct()

    return savedOrder + DefaultLibraryFilterOrder.filterNot(savedOrder::contains)
}

fun List<LibraryFilter>.toLibraryFilterPreference(): String {
    val orderedFilters = distinct()
    return (orderedFilters + DefaultLibraryFilterOrder.filterNot(orderedFilters::contains))
        .joinToString(",") { it.name }
}
