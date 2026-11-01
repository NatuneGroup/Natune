package moe.rukamori.archivetune.shared.domain.playlist

import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/** The first rows of a local playlist feed its 2x2 mosaic cover, in the playlist's own order. */
const val PLAYLIST_MOSAIC_LIMIT = 4

/**
 * The items the local playlist mosaic draws: the first [limit] rows of the playlist in their stored order. A row
 * without artwork stays in the mosaic and renders as an empty tile, as the playlist's own first songs do.
 */
fun playlistMosaicItems(occurrences: List<ContentOccurrence>, limit: Int = PLAYLIST_MOSAIC_LIMIT): List<ContentItem> =
    occurrences.take(limit).map { it.item }
