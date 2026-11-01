package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.DownloadSourceConfig

/**
 * The one gate every in-place playback-recovery retry passes through, fusing the small policies
 * the error paths used to consult separately: [PlaybackStreamRecoveryTracker]'s per-item attempt
 * budget, the shared [PlaybackRetryBackoff] delay curve, and [RebufferMonitor]'s freeze.
 * Stream-resolution retries and player-error recovery therefore share one bounded backoff, and a
 * track that keeps rebuffering can no longer loop re-prepares.
 *
 * [scope], [player] and [playerCache] are read lazily: the service recreates its scope after a
 * destroy, and the session-facing player is replaced when a crossfade promotes the incoming
 * player — a captured reference would re-prepare the released instance.
 */
internal class PlaybackRecoveryRetry(
    private val scope: () -> CoroutineScope,
    private val player: () -> Player,
    private val tracker: PlaybackStreamRecoveryTracker,
    private val rebuffer: RebufferMonitor,
    private val playerCache: () -> Cache,
    private val backoff: PlaybackRetryBackoff = PlaybackRetryBackoff(),
    private val fire: (mediaId: String, resumePlaying: Boolean, seekIndex: Int, seekPositionMs: Long) -> Unit =
        ::fireOnPlayer,
) {
    /** At most one delayed retry is ever outstanding; [generation] invalidates it on supersession. */
    private var pending: Job? = null
    private var generation = 0L

    /**
     * True when this media item may still retry in place: the rebuffer freeze has not fired for
     * it, and the per-item attempt budget funded one more attempt, which is spent here. A frozen
     * track spends nothing, so its failure falls through to the terminal recovery policy instead
     * of looping.
     */
    fun allow(mediaId: String): Boolean =
        !rebuffer.shouldSuppressRetries(mediaId) && tracker.registerRetryAttempt(mediaId)

    /**
     * Re-prepares [mediaId] after the unified backoff delay for the attempt the tracker already
     * recorded — attempt one is immediate, later attempts wait out the jittered curve — and never
     * sooner than [minDelayMs] (the codec path's hardware-teardown floor). An optional
     * [seekIndex]/[seekPositionMs] restores the position callers used to seek to by hand before
     * preparing. The fire itself runs [fireOnPlayer], which rechecks the current item: a delayed
     * prepare must not resurrect a load the user already moved past. Any newer [prepare],
     * [cancelPending], or event hook supersedes the pending call.
     */
    fun prepare(
        mediaId: String,
        resumePlaying: Boolean,
        seekIndex: Int = C.INDEX_UNSET,
        seekPositionMs: Long = 0L,
        minDelayMs: Long = 0L,
        onFailure: ((Throwable) -> Unit)? = null,
    ) {
        pending?.cancel()
        val expected = ++generation
        val delayMs = maxOf(backoff.delayMs(tracker.attemptCount(mediaId)), minDelayMs)
        if (delayMs <= 0L) {
            dispatch(mediaId, resumePlaying, seekIndex, seekPositionMs, onFailure)
            return
        }
        pending = scope().launch {
            delay(delayMs)
            if (expected != generation) return@launch
            pending = null
            dispatch(mediaId, resumePlaying, seekIndex, seekPositionMs, onFailure)
        }
    }

    private fun dispatch(
        mediaId: String,
        resumePlaying: Boolean,
        seekIndex: Int,
        seekPositionMs: Long,
        onFailure: ((Throwable) -> Unit)?,
    ) {
        if (onFailure == null) {
            fire(mediaId, resumePlaying, seekIndex, seekPositionMs)
        } else {
            runCatching { fire(mediaId, resumePlaying, seekIndex, seekPositionMs) }
                .onFailure { onFailure(it) }
        }
    }

    private fun fireOnPlayer(mediaId: String, resumePlaying: Boolean, seekIndex: Int, seekPositionMs: Long) {
        val player = player()
        if (player.currentMediaItem?.mediaId != mediaId) return
        if (seekIndex != C.INDEX_UNSET) player.seekTo(seekIndex, seekPositionMs)
        player.prepare()
        if (resumePlaying && player.playWhenReady) player.play()
    }

    /** A user or lifecycle action supersedes any pending retry: stop, seek, transition, player swap. */
    fun cancelPending() {
        generation++
        pending?.cancel()
        pending = null
    }

    /** A new media item — even the same id again — ends the old track's stall streak and its retry. */
    fun onMediaItemTransition(mediaId: String?) {
        rebuffer.onMediaItemChanged(mediaId)
        cancelPending()
    }

    /** A seek is user intent catching up, not evidence against the link; it also supersedes retries. */
    fun onSeek() {
        rebuffer.onSeek()
        cancelPending()
    }

    /** A stall the user paused inside is not rebuffering they suffered through. */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) = rebuffer.onPlayWhenReadyChanged(playWhenReady)

    /** The quality a (re-)resolve for [mediaId] asks for: the configured tier, or LOW once degraded. */
    fun audioQuality(mediaId: String, lowData: Boolean, configured: AudioQuality): AudioQuality =
        if (lowData || !rebuffer.shouldStepDownQuality(mediaId)) {
            if (lowData) AudioQuality.LOW else configured
        } else {
            retirePartialPlayerCacheSpans(mediaId)
            AudioQuality.LOW
        }

    /**
     * The LOW rendition reuses the same playback-cache key, so partial spans fetched under HIGH
     * would mix with the replacement's LOW bytes. Retire those spans — but never a complete item,
     * which is self-consistent whichever rendition it holds — and never the user's downloads:
     * only the ephemeral playback cache is touched. Chosen over rendition-scoped cache keys
     * because scoping would change every resolver/downloader key path for one edge case.
     */
    private fun retirePartialPlayerCacheSpans(mediaId: String) {
        val cache = runCatching { playerCache() }.getOrNull() ?: return
        for (key in DownloadSourceConfig.cacheKeysFor(mediaId)) {
            val complete =
                runCatching {
                    val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                    length > 0L && cache.isCached(key, 0L, length)
                }.getOrDefault(false)
            if (!complete) runCatching { cache.removeResource(key) }
        }
    }
}
