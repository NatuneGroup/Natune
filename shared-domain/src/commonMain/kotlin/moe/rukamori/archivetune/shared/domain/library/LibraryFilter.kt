package moe.rukamori.archivetune.shared.domain.library

/** A Library section chip. Stored by name, so the names are part of the contract. */
enum class LibraryFilter {
    SONGS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
    LIBRARY,
}

/** The chip order a Library shows before the user moves anything. */
val DefaultLibraryFilterOrder: List<LibraryFilter> =
    listOf(
        LibraryFilter.LIBRARY,
        LibraryFilter.PLAYLISTS,
        LibraryFilter.SONGS,
        LibraryFilter.ARTISTS,
        LibraryFilter.ALBUMS,
    )

val DefaultLibraryFilterOrderPreference: String = DefaultLibraryFilterOrder.joinToString(",") { it.name }

/**
 * Decodes a stored chip order. Names are trimmed and matched exactly, unknown and repeated names are dropped, and any
 * section missing from the stored order is appended in default order, so an upgrade never hides a section.
 */
fun String.toLibraryFilterOrder(): List<LibraryFilter> {
    val savedOrder =
        split(",")
            .mapNotNull { savedName -> LibraryFilter.entries.firstOrNull { it.name == savedName.trim() } }
            .distinct()
    return savedOrder + DefaultLibraryFilterOrder.filterNot(savedOrder::contains)
}

/** Encodes a chip order as stored. Repeats are dropped and missing sections are appended in default order. */
fun List<LibraryFilter>.toLibraryFilterPreference(): String {
    val orderedFilters = distinct()
    return (orderedFilters + DefaultLibraryFilterOrder.filterNot(orderedFilters::contains))
        .joinToString(",") { it.name }
}
