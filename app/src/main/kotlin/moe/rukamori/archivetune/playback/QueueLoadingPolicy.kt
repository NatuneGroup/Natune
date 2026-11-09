package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue

internal fun mayStartQueueLoading(
    suppressed: Boolean,
    hydrating: Boolean,
    finiteMutation: Boolean,
    timelineMutation: Boolean,
): Boolean = !suppressed && !hydrating && !finiteMutation && !timelineMutation

internal fun evaluateAcceptedQueueLoading(
    queue: Queue,
    allowed: Boolean,
    needsFiniteItems: () -> Boolean,
    startFinite: (YouTubePlaylistQueue) -> Unit,
    considerRecommendations: (Queue) -> Unit,
    allowRecommendations: Boolean = true,
) {
    if (!allowed) return
    // Finite membership is independent of the recommendations toggle and repeat policy.
    (queue as? YouTubePlaylistQueue)?.takeIf { it.hasNextPage() && needsFiniteItems() }?.let(startFinite)
    if (allowRecommendations) considerRecommendations(queue)
}

internal fun Player.finiteQueueNeedsMoreItems(): Boolean {
    if (mediaItemCount == 0 || currentTimeline.isEmpty) return true
    val timeline = currentTimeline
    var index = currentMediaItemIndex
    repeat(5) {
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        if (index == C.INDEX_UNSET) return true
    }
    return false
}

internal fun Queue.shouldBootstrapInfiniteQueue(): Boolean =
    if (this is ListQueue && finite) false else if (this is YouTubePlaylistQueue) confirmedExhausted else preloadItem != null || !hasNextPage()

internal fun Queue.infiniteQueueSeedMediaId(): String? =
    if (this is YouTubePlaylistQueue) null else preloadItem?.id?.trim()?.takeIf { it.isNotBlank() }
