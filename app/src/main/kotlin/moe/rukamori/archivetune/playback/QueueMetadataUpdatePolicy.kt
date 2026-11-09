package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.models.MediaMetadata

/** The fields changed by catalog enrichment, artwork fallback and song-name edits. */
internal data class QueueMetadataPresentation(
    val title: String?,
    val subtitle: String?,
    val artist: String?,
    val albumTitle: String?,
    val artworkUri: String?,
)

/** Only explicit name/album edits replace queue rows; artwork uses the now-playing projection. */
internal fun queueMetadataNeedsReplacement(
    previous: QueueMetadataPresentation,
    updated: QueueMetadataPresentation,
): Boolean =
    previous.copy(artworkUri = updated.artworkUri) != updated

/** Keep notification-sized artwork unless enrichment changed it and the occurrence is current. */
internal fun queueMetadataArtworkUri(
    previous: String?,
    resolved: String?,
    isCurrentItem: Boolean,
    artworkChanged: Boolean,
): String? = if (isCurrentItem && artworkChanged) resolved ?: previous else previous

/** Enrichment belongs to the song; playlist occurrence identity and attribution belong to the row. */
internal fun resolvedQueueMetadataForOccurrence(
    occurrence: MediaMetadata,
    resolved: MediaMetadata,
): MediaMetadata =
    resolved.copy(
        id = occurrence.id,
        setVideoId = occurrence.setVideoId ?: resolved.setVideoId,
        suggestedBy = occurrence.suggestedBy ?: resolved.suggestedBy,
    )

internal data class QueueMetadataReplacementBatch<T>(
    val fromIndex: Int,
    val items: List<T>,
) {
    val toIndex: Int get() = fromIndex + items.size
}

/** Replace contiguous changed occurrences only: an intervening playing source must survive. */
internal fun <T : Any> queueMetadataReplacementBatches(
    original: List<T>,
    updates: Map<Int, T>,
): List<QueueMetadataReplacementBatch<T>> {
    require(updates.keys.all { it in original.indices })
    val batches = mutableListOf<QueueMetadataReplacementBatch<T>>()
    val indices = updates.keys.sorted()
    var cursor = 0
    while (cursor < indices.size) {
        val from = indices[cursor]
        val items = mutableListOf<T>()
        do {
            items.add(updates.getValue(indices[cursor++]))
        } while (cursor < indices.size && indices[cursor] == from + items.size)
        batches.add(QueueMetadataReplacementBatch(from, items))
    }
    return batches
}
