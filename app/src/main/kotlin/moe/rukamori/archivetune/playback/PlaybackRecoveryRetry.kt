package moe.rukamori.archivetune.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.AudioQuality

/**
 * The one gate every in-place playback-recovery retry passes through, fusing the small policies
 * the error paths used to consult separately: [PlaybackStreamRecoveryTracker]'s per-item attempt
 * budget, the shared [PlaybackRetryBackoff] delay curve, and [RebufferMonitor]'s freeze.
 * Stream-resolution retries and player-error recovery therefore share one bounded backoff, and a
 * track that keeps rebuffering can no longer loop re-prepares.
 *
 * [scope] and [player] are read lazily: the service recreates its scope after a destroy, and the
 * session-facing player is replaced when a crossfade promotes the incoming player — a captured
 * reference would re-prepare the released instance.
 */
internal class PlaybackRecoveryRetry(
    private val scope: () -> CoroutineScope,
    private val player: () -> Player,
    private val tracker: PlaybackStreamRecoveryTracker,
    private val rebuffer: RebufferMonitor,
    private val backoff: PlaybackRetryBackoff = PlaybackRetryBackoff(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
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
     * a user seek — the count can, but a seek the player ignores produces no discontinuity at
     * all, so the marks also die at [recoverySeeksDeadlineMs] instead of standing forever.
     */
    private var recoverySeeksPending = 0
    private var recoverySeeksDeadlineMs = Long.MIN_VALUE

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
        // The discontinuity a recovery seek produces arrives on this thread only after the fire
        // returns; mark it, but bounded — a seek that never happens or is ignored must not keep
        // the mark alive long enough to swallow a later user seek.
        if (seekIndex != C.INDEX_UNSET) {
            recoverySeeksPending++
            recoverySeeksDeadlineMs = nowMs() + RECOVERY_SEEK_WINDOW_MS
        }
        if (onFailure == null) {
            try {
                fireImpl(mediaId, resumePlaying, seekIndex, seekPositionMs)
            } catch (t: Throwable) {
                if (seekIndex != C.INDEX_UNSET) recoverySeeksPending--
                throw t
            }
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
        if (player.currentMediaItem?.mediaId != mediaId) {
            // Never issued: no discontinuity will follow, so the mark must not stand.
            if (seekIndex != C.INDEX_UNSET) recoverySeeksPending--
            return
        }
        if (seekIndex != C.INDEX_UNSET) {
            // Media3 silently ignores a seek whose index fell out of the timeline while the
            // backoff ran — it produces no discontinuity, so it must not stay marked.
            if (seekIndex in 0 until player.currentTimeline.windowCount) {
                player.seekTo(seekIndex, seekPositionMs)
            } else {
                recoverySeeksPending--
            }
        }
        player.prepare()
        if (resumePlaying && player.playWhenReady) player.play()
    }

    /** A user or lifecycle action supersedes any pending retry: stop, seek, transition, player swap. */
    fun cancelPending() {
        generation++
        pending?.cancel()
        pending = null
        recoverySeeksPending = 0
        recoverySeeksDeadlineMs = Long.MIN_VALUE
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
        // Consume the deferred discontinuity a recovery seek produced — but only while its mark
        // is still fresh; an ignored or dropped seek must not swallow the next user seek forever.
        if (recoverySeeksPending > 0 && nowMs() <= recoverySeeksDeadlineMs) {
            recoverySeeksPending--
            return
        }
        recoverySeeksPending = 0
        recoverySeeksDeadlineMs = Long.MIN_VALUE
        rebuffer.onSeek()
        cancelPending()
    }

    /**
     * Pausing ends a stall where it stands, and a deliberate pause supersedes a pending retry:
     * it must not surprise-resume after the user asked for quiet.
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (!playWhenReady) cancelPending()
        rebuffer.onPlayWhenReadyChanged(playWhenReady)
    }

    /** The quality a (re-)resolve for [mediaId] asks for: the configured tier, or LOW once degraded. */
    fun audioQuality(mediaId: String, lowData: Boolean, configured: AudioQuality): AudioQuality =
        if (lowData || rebuffer.shouldStepDownQuality(mediaId)) AudioQuality.LOW else configured

    private companion object {
        /** The longest a recovery seek's deferred discontinuity may take before its mark expires. */
        const val RECOVERY_SEEK_WINDOW_MS = 2_000L
    }
}
