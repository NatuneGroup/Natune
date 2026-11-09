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

/** Tags and artwork for a non-current occurrence must not republish the session's queue. */
internal fun queueMetadataNeedsReplacement(
    previous: QueueMetadataPresentation,
    updated: QueueMetadataPresentation,
    isCurrentItem: Boolean,
): Boolean =
    if (isCurrentItem) previous != updated
    else previous.copy(artworkUri = updated.artworkUri) != updated

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

/** One replacement for all changed occurrences, retaining every intervening row and its order. */
internal fun <T : Any> queueMetadataReplacementBatch(
    original: List<T>,
    updates: Map<Int, T>,
): QueueMetadataReplacementBatch<T>? {
    if (updates.isEmpty()) return null
    require(updates.keys.all { it in original.indices })
    val from = updates.keys.minOrNull() ?: return null
    val to = (updates.keys.maxOrNull() ?: return null) + 1
    return QueueMetadataReplacementBatch(from, (from until to).map { updates[it] ?: original[it] })
}
