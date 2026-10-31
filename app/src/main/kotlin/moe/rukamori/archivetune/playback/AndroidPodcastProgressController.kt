package moe.rukamori.archivetune.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.db.AndroidPodcastProgressStore
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressRecorder
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressSample
import moe.rukamori.archivetune.utils.isLocalMediaId
import timber.log.Timber

/** Follows the actual session-facing player, including Cast and local-player promotions. Main-thread confined. */
class AndroidPodcastProgressController(database: MusicDatabase) : Player.Listener {
    // The final FIFO drain must survive MusicService cancelling its own scope during onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: Player? = null
    private var ticker: Job? = null
    private var occurrence = 0L
    private var discontinuity = 0L
    private var transition = false
    private var last: PodcastProgressSample? = null
    private val recorder = PodcastProgressRecorder(
        scope = scope,
        store = AndroidPodcastProgressStore(database.podcastProgress),
        seekTo = { player?.seekTo(it) },
        report = { Timber.tag("PodcastProgress").w(it) },
        nowMs = System::currentTimeMillis,
    )

    fun attach(next: Player) {
        if (player === next) return
        sample()
        recorder.observe(null)
        player?.removeListener(this)
        ticker?.cancel()
        player = next
        occurrence++
        transition = false
        last = null
        next.addListener(this)
        sample()
        if (next.isPlaying) startTicker()
    }

    fun detach() {
        sample()
        recorder.flush()
        player?.removeListener(this)
        player = null
        ticker?.cancel()
        ticker = null
        val drained = recorder.finish()
        scope.launch {
            drained.join()
            scope.cancel()
        }
    }

    // Positions only move while playing, so the 500 ms sampler runs only for that span. Pause and
    // seek are still sampled from onEvents, which runs after onIsPlayingChanged in the same batch.
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                sample()
                delay(500L)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startTicker()
        } else {
            ticker?.cancel()
            ticker = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        transition = true
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // The player's current item is already the NEW one. PositionInfo is the only exact outgoing bookmark.
        val naturalEnd = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
            (oldPosition.mediaItemIndex != newPosition.mediaItemIndex || newPosition.positionMs < oldPosition.positionMs)
        last?.takeIf { it.episodeId.substringAfter(':') == oldPosition.mediaItem?.mediaId }?.let {
            recorder.observe(it.copy(
                positionMs = oldPosition.positionMs,
                monotonicMs = SystemClock.elapsedRealtime(),
                ended = it.ended || naturalEnd,
            ))
            recorder.flush()
        }
        discontinuity++
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
            naturalEnd || reason == Player.DISCONTINUITY_REASON_REMOVE
        ) transition = true
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (this.player !== player) return
        if (transition) {
            recorder.observe(null)
            occurrence++
            transition = false
            last = null
        }
        sample()
    }

    private fun sample() {
        val player = player ?: return
        val item = player.currentMediaItem
        if (item?.metadata?.isPodcast != true) {
            recorder.observe(null)
            last = null
            return
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: item.metadata?.duration?.takeIf { it > 0 }?.times(1_000L)
        val provider = if (item.mediaId.isLocalMediaId()) "local" else "youtube"
        val sample = PodcastProgressSample(
            occurrenceId = "$occurrence:${item.mediaId}",
            episodeId = "$provider:${item.mediaId}",
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            advancing = player.isPlaying,
            seekable = player.isCurrentMediaItemSeekable,
            discontinuity = discontinuity,
            monotonicMs = SystemClock.elapsedRealtime(),
            ended = player.playbackState == Player.STATE_ENDED,
        )
        last = sample
        recorder.observe(sample)
    }
}
