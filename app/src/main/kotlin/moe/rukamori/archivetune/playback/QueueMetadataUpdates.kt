package moe.rukamori.archivetune.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata as PlatformMetadata
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
    private val onCurrentMetadataChanged: () -> Unit = {},
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
        if (player.currentMediaItem?.mediaId == mediaId) publishCurrentMetadata()
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
        publish(player) { item ->
            val edit = edits[item.mediaId]
            if (edit == null) item else item.withEffectiveSongMetadata(edit)
        }
        if (currentId != null && currentId in edits) publishCurrentMetadata()
    }

    fun commitArtwork(mediaId: String, url: String) {
        val player = playerProvider()
        val item = player.currentMediaItem?.takeIf { it.mediaId == mediaId } ?: return
        val metadata = metadataFor(item) ?: return
        resolvedByMediaId[mediaId] = metadata.canonicalMetadata().copy(thumbnailUrl = url)
        changedArtworkMediaIds.add(mediaId)
        publishCurrentMetadata()
    }

    /** Publish resolved tags/artwork to system surfaces without touching the playlist timeline. */
    fun publishCurrentMetadata() {
        val player = playerProvider()
        currentMetadata.value = player.currentMediaItem?.let(::metadataFor)
        onCurrentMetadataChanged()
    }

    fun sessionMetadata(item: MediaItem?, previous: PlatformMetadata): PlatformMetadata {
        val currentItem = item ?: return previous
        val effective = metadataFor(currentItem) ?: return previous
        val names = effective.artists.joinToString { it.name }
        // Keep the original notification-sized URI if the catalog artwork itself is unchanged.
        val artwork = queueMetadataArtworkUri(
            previous = previous.artworkUri?.toString(),
            resolved = effective.thumbnailUrl,
            isCurrentItem = true,
            artworkChanged = currentItem.mediaId in changedArtworkMediaIds,
        )?.toUri()
        return previous.buildUpon()
            .setTitle(effective.title)
            .setSubtitle(names)
            .setArtist(names)
            .setAlbumTitle(effective.album?.title)
            .setArtworkUri(artwork)
            .build()
    }

    private fun publish(player: Player, transform: (MediaItem) -> MediaItem) {
        val original = player.mediaItems.toList()
        val updates = buildMap {
            original.forEachIndexed { index, item ->
                val updated = transform(item)
                if (queueMetadataNeedsReplacement(item.presentation(), updated.presentation())) {
                    put(index, updated)
                }
            }
        }
        for (batch in queueMetadataReplacementBatches(original, updates)) {
            runCatching { player.replaceMediaItems(batch.fromIndex, batch.toIndex, batch.items) }.onFailure {
                Timber.tag("MusicService").w(it, "metadata: failed to update queued presentation")
            }
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
