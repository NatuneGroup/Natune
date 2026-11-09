package moe.rukamori.archivetune.ui.screens.playlist

import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.queues.ListQueue
import kotlin.random.Random

/**
 * The saved copy plays from the device. A YouTube playlist queue refetches the playlist first,
 * which is exactly what fails while the saved copy stands in for an unreachable fetch.
 */
internal fun offlineCopyAction(
    showingOfflineCopy: Boolean,
    action: () -> Unit,
): (() -> Unit)? = action.takeIf { showingOfflineCopy }

internal class OfflineCopyQueueOrder<T>(
    val items: List<T>,
    val startIndex: Int,
)

internal fun <T> offlineCopyQueueOrder(
    songs: List<T>,
    startIndex: Int = 0,
    shuffle: Boolean = false,
    random: Random = Random.Default,
): OfflineCopyQueueOrder<T> =
    if (shuffle) {
        OfflineCopyQueueOrder(songs.shuffled(random), 0)
    } else {
        OfflineCopyQueueOrder(songs, if (songs.isEmpty()) 0 else startIndex.coerceIn(songs.indices))
    }

internal fun List<SongItem>.offlineCopyQueue(
    title: String,
    startIndex: Int = 0,
    shuffle: Boolean = false,
): ListQueue {
    val order = offlineCopyQueueOrder(this, startIndex, shuffle)
    return ListQueue(
        title = title,
        items = order.items.map { it.toMediaItem() },
        startIndex = order.startIndex,
        finite = true,
    )
}

internal fun SongItem.toPlaylistPlaybackEndpoint(
    playlistId: String,
    playlistPlayParams: String?,
): WatchEndpoint {
    val baseEndpoint = endpoint ?: WatchEndpoint(videoId = id)
    return baseEndpoint.copy(
        videoId = baseEndpoint.videoId ?: id,
        playlistId = playlistId,
        index = baseEndpoint.index.takeIf {
            baseEndpoint.playlistId?.removePrefix("VL") == playlistId.removePrefix("VL")
        },
        playlistSetVideoId = baseEndpoint.playlistSetVideoId ?: setVideoId,
        params = baseEndpoint.params ?: playlistPlayParams,
    )
}
