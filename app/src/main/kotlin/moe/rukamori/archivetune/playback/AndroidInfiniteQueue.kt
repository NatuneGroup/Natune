package moe.rukamori.archivetune.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.playback.queues.appendableQueuePage
import moe.rukamori.archivetune.shared.domain.queue.InfiniteQueueLoadGate
import moe.rukamori.archivetune.shared.domain.queue.InfiniteQueuePolicy
import timber.log.Timber

/** Service-owned recommendation work. Finite browse paging and audio resolution never enter this owner. */
internal class AndroidInfiniteQueue(
    private val scope: () -> CoroutineScope,
    private val player: () -> Player,
    private val queue: () -> Queue,
    private val enabled: () -> Boolean,
    private val mayLoad: () -> Boolean,
    private val maySeed: (Queue) -> Boolean,
    private val isLocal: () -> Boolean,
    private val filter: suspend (List<MediaItem>) -> List<MediaItem>,
    private val attachRadio: (Queue) -> Unit,
    private val markAutomatic: (List<MediaItem>) -> Unit,
    private val setShuffleOrder: (IntArray) -> Unit,
    private val save: suspend () -> Unit,
    private val loading: MutableStateFlow<Boolean>,
) {
    private val gate = InfiniteQueueLoadGate()
    private var job: Job? = null
    private var generation = 0L

    fun cancel(newQueue: Boolean = false) {
        generation++
        gate.invalidate(newQueue)
        job?.cancel()
        job = null
        loading.value = false
    }

    fun consider(seedMediaId: String?) {
        if (!mayLoad() || !enabled() || isLocal() || job?.isActive == true) return
        val original = queue()
        val target = player()
        val continuation = original !is YouTubePlaylistQueue && original.hasNextPage()
        if (!continuation && !maySeed(original)) return
        val seed = seedMediaId?.trim()?.takeIf { it.isNotBlank() }
            ?: target.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() } ?: return
        val ticket = gate.begin(
            enabled(), target.repeatMode == Player.REPEAT_MODE_OFF, remaining(target), continuation, SystemClock.elapsedRealtime(),
        ) ?: return
        val epoch = ++generation
        loading.value = true
        job = scope().launch(start = CoroutineStart.LAZY) {
            var added = 0
            try {
                withTimeoutOrNull(InfiniteQueuePolicy.REQUEST_TIMEOUT_MS) {
                    val source = if (continuation) original else YouTubeQueue(
                        WatchEndpoint(videoId = seed), followAutomixPreview = true,
                    )
                    var items = filter(withContext(Dispatchers.IO) {
                        if (continuation) appendableQueuePage(source.nextPage(), source.nextPageRepeatsCurrentItem)
                        else source.getInitialStatus().items
                    })
                    val recommendations = source is YouTubeQueue
                    var pages = 1
                    while (recommendations && fresh(target, items).isEmpty() && source.hasNextPage() &&
                        pages < InfiniteQueuePolicy.MAX_BOOTSTRAP_PAGES
                    ) {
                        pages++
                        items = filter(withContext(Dispatchers.IO) {
                            appendableQueuePage(source.nextPage(), source.nextPageRepeatsCurrentItem)
                        })
                    }
                    if (epoch != generation || !gate.isCurrent(ticket) || queue() !== original ||
                        player() !== target || !enabled() || !mayLoad() || target.repeatMode != Player.REPEAT_MODE_OFF
                    ) return@withTimeoutOrNull

                    // A manual addition may have arrived during IO; use the live timeline again at commit.
                    val append = if (recommendations) fresh(target, items) else items
                    val ended = target.playbackState == Player.STATE_ENDED
                    val resume = target.playWhenReady
                    if (!continuation) attachRadio(source)
                    if (append.isNotEmpty()) {
                        val previousCount = target.mediaItemCount
                        val order = if (target.shuffleModeEnabled) target.getQueueWindows().map { it.firstPeriodIndex } else null
                        target.addMediaItems(append)
                        if (order != null) setShuffleOrder((order + (previousCount until target.mediaItemCount)).toIntArray())
                        if (recommendations) markAutomatic(append)
                        added = append.size
                        // Never skip a still-playing last song or resume an intentional pause.
                        if (ended && target.hasNextMediaItem()) {
                            target.seekToNextMediaItem()
                            target.prepare()
                            if (resume) target.play()
                        }
                    }
                    save()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.e(failure, "Failed to extend the playback queue")
            } finally {
                gate.finish(ticket, added, SystemClock.elapsedRealtime())
                if (epoch == generation) {
                    job = null
                    loading.value = false
                }
            }
        }.also { it.start() }
    }

    private fun fresh(target: Player, items: List<MediaItem>): List<MediaItem> =
        InfiniteQueuePolicy.fresh(items, (0 until target.mediaItemCount).map { target.getMediaItemAt(it).mediaId }) {
            it.mediaId
        }

    private fun remaining(target: Player): Int {
        val timeline = target.currentTimeline
        var index = target.currentMediaItemIndex
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return 0
        var count = 1
        while (count <= InfiniteQueuePolicy.PAGE_REMAINING_ITEMS) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, target.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            count++
        }
        return count
    }
}
