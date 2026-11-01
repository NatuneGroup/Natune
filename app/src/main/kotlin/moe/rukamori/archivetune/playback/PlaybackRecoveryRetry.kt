package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.AudioQuality

/**
 * The one gate every in-place playback-recovery retry passes through, fusing the three small
 * policies the error paths used to consult separately: [PlaybackStreamRecoveryTracker]'s
 * per-item attempt budget, the shared [PlaybackRetryBackoff] delay curve, and
 * [RebufferMonitor]'s freeze. Stream-resolution retries and player-error recovery therefore
 * share one bounded backoff, and a track that keeps rebuffering can no longer loop re-prepares.
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
) {
    /**
     * True when this media item may still retry in place: the rebuffer freeze has not fired for
     * it, and the per-item attempt budget funded one more attempt, which is spent here. A frozen
     * track spends nothing, so its failure falls through to the terminal recovery policy instead
     * of looping.
     */
    fun allow(mediaId: String): Boolean =
        !rebuffer.shouldSuppressRetries(mediaId) && tracker.registerRetryAttempt(mediaId)

    /**
     * Re-prepares [mediaId] after the backoff delay for the attempt the tracker just recorded —
     * attempt one is immediate, later attempts wait out the jittered curve. A delayed prepare
     * re-checks the current item first: a skip or a crossfade promotion during the wait must not
     * resurrect the failed load. [resumePlaying] mirrors the play() the old sites ran after
     * prepare; playWhenReady alone already resumes, but the explicit call kept the intent.
     */
    fun prepare(mediaId: String, resumePlaying: Boolean) {
        val delayMs = backoff.delayMs(tracker.attemptCount(mediaId))
        if (delayMs <= 0L) {
            player().prepare()
            if (resumePlaying) player().play()
            return
        }
        scope().launch {
            delay(delayMs)
            val player = player()
            if (player.currentMediaItem?.mediaId != mediaId) return@launch
            player.prepare()
            if (resumePlaying && player.playWhenReady) player.play()
        }
    }

    /** The quality a (re-)resolve for [mediaId] asks for: the configured tier, or LOW once degraded. */
    fun audioQuality(mediaId: String, lowData: Boolean, configured: AudioQuality): AudioQuality =
        if (lowData || rebuffer.shouldStepDownQuality(mediaId)) AudioQuality.LOW else configured
}
