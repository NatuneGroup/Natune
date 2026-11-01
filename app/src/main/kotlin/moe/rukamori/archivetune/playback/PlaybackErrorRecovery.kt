package moe.rukamori.archivetune.playback

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureClassifier
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryAction
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryBudget
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryPolicy
import moe.rukamori.archivetune.shared.domain.playback.playbackCauses

/**
 * The terminal decision of [MusicService.onPlayerError], reached once every specialised in-place
 * recovery has run or declined. The shared [PlaybackRecoveryPolicy] classifies the failure and
 * spends one attempt of the failure stretch's budget; a recoverable category that still holds a
 * resolve request earns an auto-advance past the dead item instead of stalling on it. The budget's
 * [PlaybackRecoveryBudget.REQUESTS] cap means at most two consecutive item failures buy an advance
 * inside one [PlaybackRecoveryBudget.WINDOW_MS] stretch — the same "cap 2" the study prescribes —
 * before the queue parks on the next failure. Progress of thirty seconds since the last failure
 * refunds the budget, so a queue that moved on is not retired.
 *
 * Main-thread only, like the listener callbacks that drive it.
 */
internal class PlaybackErrorRecovery(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var budget: PlaybackRecoveryBudget? = null

    /** Playback produced audio again: the failure stretch is over and the next one starts fresh. */
    fun onPlaybackRecovered() {
        budget = null
    }

    /** The queue itself was torn down; no stretch may carry across the next queue. */
    fun reset() {
        budget = null
    }

    /**
     * True when the policy still funds the failure stretch — the failure classified as a
     * recoverable kind inside the request budget — so the caller advances past the item. The
     * advance itself spends one resolve request: it is this path's scarce recovery resource, which
     * is what bounds the chain at [PlaybackRecoveryBudget.REQUESTS] advances per stretch.
     */
    fun shouldAdvancePast(failure: PlaybackFailure, positionMs: Long): Boolean {
        val now = nowMs()
        val stretch = (budget ?: PlaybackRecoveryBudget.starting(now)).forPlaybackFailure(positionMs, now)
        val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureClassifier.categorize(failure), stretch, now)
        var remaining = decision.budget
        if (decision.action == PlaybackRecoveryAction.NONE) {
            budget = null
            return false
        }
        if (remaining.requestsRemaining <= 0) {
            budget = remaining
            return false
        }
        budget = remaining.consumeRequest()
        return true
    }
}

/**
 * A Media3 failure expressed for [PlaybackRecoveryPolicy]: the coarse error-code groups map onto the
 * shared reasons exactly as [Media3PlaybackPort] reports them, and the cause chain flattens the way
 * the error dialog's details do.
 */
internal fun PlaybackException.asPlaybackFailure(mediaId: String, httpStatus: Int? = null): PlaybackFailure =
    PlaybackFailure(
        occurrenceId = mediaId,
        reason = failureReasonFor(errorCode),
        message = message ?: "Media3 error $errorCode",
        httpStatus = httpStatus,
        causes = playbackCauses(),
    )

/** Media3 error codes are grouped by thousands: 2xxx I/O, 5xxx audio renderer — mirrored from Media3PlaybackPort. */
internal fun failureReasonFor(errorCode: Int): PlaybackFailureReason = when (errorCode) {
    in 2000..2999 -> PlaybackFailureReason.OPEN_FAILED
    in 5000..5999 -> PlaybackFailureReason.OUTPUT_FAILED
    else -> PlaybackFailureReason.DECODE_FAILED
}

/** Debug-only helper: short human-readable description of the cause chain for logging. */
internal fun describeCauseChain(error: Throwable): String {
    val causeChain = generateSequence<Throwable>(error) { it.cause }
    return causeChain
        .take(5)
        .joinToString(" -> ") { t ->
            val cls = t.javaClass.simpleName
            val msg = t.message?.take(80)?.replace("\n", " ")?.trim().orEmpty()
            if (msg.isEmpty()) cls else "$cls($msg)"
        }
}
