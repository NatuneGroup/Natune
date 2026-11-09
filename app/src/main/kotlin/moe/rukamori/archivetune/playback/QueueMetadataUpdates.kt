package moe.rukamori.archivetune.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.extensions.mediaItems
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.withEffectiveSongMetadata
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.songmetadata.SongMetadataOverrideCache
import moe.rukamori.archivetune.songmetadata.withEffectiveSongNames
import moe.rukamori.archivetune.songmetadata.withSongMetadataOverride
import timber.log.Timber

/** Main-thread owner of resolved tags, independent of Media3 timeline publications. */
internal class QueueMetadataUpdates(
    private val playerProvider: () -> Player,
    private val currentMetadata: MutableStateFlow<MediaMetadata?>,
) {
    private val resolvedByMediaId = HashMap<String, MediaMetadata>()
    private val changedArtworkMediaIds = HashSet<String>()

    fun metadataFor(item: MediaItem): MediaMetadata? {
        val occurrence = item.metadata ?: return null
        val resolved = resolvedByMediaId[item.mediaId] ?: return occurrence
        return resolvedQueueMetadataForOccurrence(occurrence, resolved)
            .withSongMetadataOverride(SongMetadataOverrideCache.get(item.mediaId))
    }

    fun retainMediaIds(mediaIds: Set<String>) {
        resolvedByMediaId.keys.retainAll(mediaIds)
        changedArtworkMediaIds.retainAll(mediaIds)
    }

    fun commitCatalog(mediaId: String, resolved: MediaMetadata) {
        val player = playerProvider()
        val occurrences = player.mediaItems.filter { it.mediaId == mediaId }
        if (occurrences.isEmpty()) return
        if (occurrences.any { it.metadata?.thumbnailUrl != resolved.thumbnailUrl }) changedArtworkMediaIds.add(mediaId)
        resolvedByMediaId[mediaId] = resolved.canonicalMetadata()
        if (player.currentMediaItem?.mediaId == mediaId) updateCurrentMetadata(player)
        publish(player) { item, isCurrent ->
            if (item.mediaId == mediaId) item.withResolvedMetadata(isCurrent) else item
        }
    }

    fun applySongEdits(edits: Map<String, MediaMetadata>) {
        val player = playerProvider()
        // Keep canonical resets and tag-only edits even when the visible names do not change.
        player.mediaItems.forEach { item ->
            val edit = edits[item.mediaId] ?: return@forEach
            val metadata = metadataFor(item) ?: return@forEach
            resolvedByMediaId[item.mediaId] = metadata.withEffectiveSongNames(edit).canonicalMetadata()
        }
        val currentId = player.currentMediaItem?.mediaId
        if (currentId != null && currentId in edits) updateCurrentMetadata(player)
        publish(player) { item, _ ->
            val edit = edits[item.mediaId]
            if (edit == null) item else item.withEffectiveSongMetadata(edit)
        }
    }

    fun commitArtwork(mediaId: String, url: String) {
        val player = playerProvider()
        val item = player.currentMediaItem?.takeIf { it.mediaId == mediaId } ?: return
        val metadata = metadataFor(item) ?: return
        resolvedByMediaId[mediaId] = metadata.canonicalMetadata().copy(thumbnailUrl = url)
        changedArtworkMediaIds.add(mediaId)
        updateCurrentMetadata(player)
        publishCurrentMetadata()
    }

    /** Deferred artwork is published only when that occurrence becomes the current track. */
    fun publishCurrentMetadata() {
        val player = playerProvider()
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId !in resolvedByMediaId) return
        publish(player) { item, isCurrent -> if (isCurrent) item.withResolvedMetadata(true) else item }
    }

    private fun updateCurrentMetadata(player: Player) {
        currentMetadata.value = player.currentMediaItem?.let(::metadataFor)
    }

    private fun MediaItem.withResolvedMetadata(isCurrent: Boolean): MediaItem {
        val effective = metadataFor(this) ?: return this
        val names = effective.artists.joinToString { it.name }
        // Keep the original notification-sized URI if the catalog artwork itself is unchanged.
        // Upcoming rows retain their platform artwork even when names also need an update.
        val artwork = queueMetadataArtworkUri(
            previous = mediaMetadata.artworkUri?.toString(),
            resolved = effective.thumbnailUrl,
            isCurrentItem = isCurrent,
            artworkChanged = mediaId in changedArtworkMediaIds,
        )?.toUri()
        return buildUpon()
            .setTag(effective)
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setTitle(effective.title)
                    .setSubtitle(names)
                    .setArtist(names)
                    .setAlbumTitle(effective.album?.title)
                    .setArtworkUri(artwork)
                    .build(),
            ).build()
    }

    private fun publish(player: Player, transform: (MediaItem, Boolean) -> MediaItem) {
        val original = player.mediaItems.toList()
        val currentIndex = player.currentMediaItemIndex
        val updates = buildMap {
            original.forEachIndexed { index, item ->
                val isCurrent = index == currentIndex
                val updated = transform(item, isCurrent)
                if (queueMetadataNeedsReplacement(item.presentation(), updated.presentation(), isCurrent)) {
                    put(index, updated)
                }
            }
        }
        val batch = queueMetadataReplacementBatch(original, updates) ?: return
        val positionMs = player.currentPosition
        runCatching { player.replaceMediaItems(batch.fromIndex, batch.toIndex, batch.items) }
            .onSuccess {
                if (currentIndex in batch.fromIndex until batch.toIndex && player.currentPosition + 1_000L < positionMs) {
                    player.seekTo(positionMs)
                }
            }.onFailure {
                Timber.tag("MusicService").w(it, "metadata: failed to update queued presentation")
            }
    }

    private fun MediaItem.presentation() =
        QueueMetadataPresentation(
            title = mediaMetadata.title?.toString(),
            subtitle = mediaMetadata.subtitle?.toString(),
            artist = mediaMetadata.artist?.toString(),
            albumTitle = mediaMetadata.albumTitle?.toString(),
            artworkUri = mediaMetadata.artworkUri?.toString(),
        )
}
