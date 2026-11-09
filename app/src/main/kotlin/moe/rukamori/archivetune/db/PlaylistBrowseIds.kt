package moe.rukamori.archivetune.db

import moe.rukamori.archivetune.playback.queues.normalizedPlaylistId

/**
 * Every stored form of one remote playlist ID, the given form first. Browse IDs reach the
 * database both with and without the "VL" browse prefix, depending on which page supplied them.
 */
internal fun playlistBrowseIdForms(browseId: String): List<String> {
    val bareId = browseId.normalizedPlaylistId()
    if (bareId.isBlank()) return emptyList()
    return listOf(browseId, bareId, "VL$bareId").distinct()
}

/** Of several stored copies of one playlist, the fullest membership; a tie keeps the earlier form. */
internal fun <T : Any> fullestPlaylistCopy(
    copies: List<T?>,
    songCount: (T) -> Int,
): T? = copies.filterNotNull().maxByOrNull(songCount)
