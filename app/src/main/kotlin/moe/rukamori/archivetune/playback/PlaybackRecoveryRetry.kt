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
    private val fire: ((mediaId: String, resumePlaying: Boolean, seekIndex: Int, seekPositionMs: Long) -> Unit)? = null,
) {
    /** A bound reference cannot live in a constructor default, so the member lands here. */
    private val fireImpl = fire ?: ::fireOnPlayer

    /** At most one delayed retry is ever outstanding; [generation] invalidates it on supersession. */
    private var pending: Job? = null
    private var generation = 0L

    /**
     * Recovery-driven seeks whose position discontinuities have not been observed yet. ExoPlayer
     * delivers a seek's discontinuity after the call returns, so a plain flag cannot tell it from
     * a user seek — the count can: the next [onSeek] per outstanding recovery seek is consumed
     * here instead of tagging the monitor or cancelling the retry it belongs to.
     */
    private var recoverySeeksPending = 0

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
     * preparing. The fire itself runs [fireImpl], which rechecks the current item: a delayed
     * prepare must not resurrect a load the user already moved past. A request that arrives
     * stale — an async refresh that resolved after its item already left — is rejected before it
     * can supersede the pending retry of whatever replaced it, and any newer [prepare],
     * [cancelPending], or event hook supersedes a pending call.
     */
    fun prepare(
        mediaId: String,
        resumePlaying: Boolean,
        seekIndex: Int = C.INDEX_UNSET,
        seekPositionMs: Long = 0L,
        minDelayMs: Long = 0L,
        onFailure: ((Throwable) -> Unit)? = null,
    ) {
        // Checked before anything is superseded: a refresh that resolved for a media item the
        // player already moved past must not cancel the new item's pending recovery.
        if (mediaId != rebuffer.currentMediaId()) return
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
        // The discontinuity the recovery seek produces arrives on this thread after the fire
        // returns; tag it now so onSeek can tell it from a user seek.
        if (seekIndex != C.INDEX_UNSET) recoverySeeksPending++
        if (onFailure == null) {
            fireImpl(mediaId, resumePlaying, seekIndex, seekPositionMs)
        } else {
            runCatching { fireImpl(mediaId, resumePlaying, seekIndex, seekPositionMs) }
                .onFailure {
                    // The seek never happened, so no recovery discontinuity is in flight.
                    if (seekIndex != C.INDEX_UNSET) recoverySeeksPending--
                    onFailure(it)
                }
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
        recoverySeeksPending = 0
    }

    /**
     * A new media item — even the same id again — ends the old track's stall streak and its
     * retry. [ready] and [buffering] carry the state the player is already in: a gapless
     * transition never leaves READY, so the monitor could not learn it from a state callback.
     */
    fun onMediaItemTransition(mediaId: String?, ready: Boolean = false, buffering: Boolean = false) {
        rebuffer.onMediaItemChanged(mediaId, ready, buffering)
        cancelPending()
    }

    /** A seek is user intent catching up, not evidence against the link; it also supersedes retries. */
    fun onSeek() {
        if (recoverySeeksPending > 0) {
            recoverySeeksPending--
            return
        }
        rebuffer.onSeek()
        cancelPending()
    }

    /** Pausing ends a stall where it stands; resuming inside buffering opens a fresh timer. */
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
     * would mix with the replacement's LOW bytes. Retiring the partials here is safe while
     * ExoPlayer may still hold a CacheDataSource on the key: SimpleCache.removeResource (via
     * removeSpan) cannot remove a locked span — a span being written or read by the live source
     * is skipped, never corrupted or yanked mid-read — so the unread tail is deleted now and any
     * locked leftovers are retired on unlock or by ordinary LRU eviction. Only keys without a
     * complete item are touched, so a finished cache entry is never disturbed, and the user's
     * downloads live in a different cache entirely. Chosen over rendition-scoped cache keys
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
