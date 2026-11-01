package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.extensions.metadata

/**
 * The album identity a transition suppression compares: the provider's album id when the queue
 * item carries one, the album title otherwise, and the media metadata's album title as the last
 * resort. Tracks without a track/disc number — which is every queue item — have no stronger
 * sequence proof than "same album, adjacent in play order", which is what the callers combine
 * this key with.
 */
internal fun gaplessAlbumKey(item: MediaItem): String? =
    item.metadata
        ?.album
        ?.id
        ?.takeIf { it.isNotBlank() }
        ?: item.metadata
            ?.album
            ?.title
            ?.takeIf { it.isNotBlank() }
        ?: item.mediaMetadata.albumTitle
            ?.toString()
            ?.takeIf { it.isNotBlank() }

/** Two queue items segue gaplessly only when they name the same album. */
internal fun isGaplessAlbumTransition(
    currentItem: MediaItem,
    targetItem: MediaItem,
): Boolean {
    val currentAlbum = gaplessAlbumKey(currentItem) ?: return false
    return currentAlbum == gaplessAlbumKey(targetItem)
}
