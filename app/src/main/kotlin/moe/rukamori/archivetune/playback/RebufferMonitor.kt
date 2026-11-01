package moe.rukamori.archivetune.playback

import android.os.SystemClock

/**
 * Counts the rebuffer stalls one track suffers and degrades or freezes it when they come too
 * fast, the way Spotify steps down instead of spinning on a saturated link. A stall counts only
 * after the track reached READY once — the initial-buffer watchdog owns the earlier phase — and
 * only while playback was wanted; a stall shorter than [MIN_STALL_MS] is a seek flicker, not a
 * rebuffer. Once [DEGRADE_EVENTS] stalls land inside [WINDOW_MS], re-resolves for the track ask
 * for low quality; once [FREEZE_EVENTS] land, in-place recovery retries stop so a dead stream
 * cannot loop re-prepares. Both decisions fade with the window: a track that then plays cleanly
 * earns its normal posture back, and a media transition always starts a fresh count.
 *
 * Main-thread only, like the listener callbacks that drive it.
 */
internal class RebufferMonitor(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var mediaId: String? = null
    private var sawReady = false

    /** Start time of the stall still open, or null. An open stall already counts provisionally. */
    private var stallOpenedAtMs: Long? = null

    /** Start times of counted stalls still inside the window. */
    private val stalls = ArrayDeque<Long>()

    /**
     * Feed every session-player state change: [buffering] and [ready] are the state the player
     * just entered, [playWhenReady] its intent. A [mediaId] different from the tracked one resets
     * the window so a stall streak can never leak onto the next track.
     */
    fun onPlaybackStateChanged(mediaId: String?, buffering: Boolean, ready: Boolean, playWhenReady: Boolean) {
        if (mediaId != this.mediaId) reset(mediaId)
        val now = nowMs()
        when {
            buffering ->
                if (playWhenReady && sawReady && stallOpenedAtMs == null) {
                    stallOpenedAtMs = now
                }
            else -> {
                if (ready) sawReady = true
                closeStall(now)
            }
        }
        prune(now)
    }

    /** True while the track's stall count asks for a low-quality re-resolve. */
    fun shouldStepDownQuality(mediaId: String): Boolean = isTracked(mediaId) && stallCount(nowMs()) >= DEGRADE_EVENTS

    /** True while the track's stall count says in-place retries must stop entirely. */
    fun shouldSuppressRetries(mediaId: String): Boolean = isTracked(mediaId) && stallCount(nowMs()) >= FREEZE_EVENTS

    private fun isTracked(mediaId: String): Boolean = this.mediaId == mediaId

    private fun stallCount(now: Long): Int = stalls.count { now - it < WINDOW_MS } + if (stallOpenedAtMs != null) 1 else 0

    private fun closeStall(now: Long) {
        val openedAt = stallOpenedAtMs ?: return
        stallOpenedAtMs = null
        if (now - openedAt >= MIN_STALL_MS) stalls.addLast(openedAt)
    }

    private fun prune(now: Long) {
        while (stalls.isNotEmpty() && now - stalls.first() >= WINDOW_MS) stalls.removeFirst()
    }

    private fun reset(newMediaId: String?) {
        mediaId = newMediaId
        sawReady = false
        stallOpenedAtMs = null
        stalls.clear()
    }

    private companion object {
        /** The stretch a stall streak must fit in before it counts against the track. */
        const val WINDOW_MS = 90_000L

        /** A buffering spell shorter than this is a seek or tickle, never a rebuffer. */
        const val MIN_STALL_MS = 1_500L

        /** Stalls inside the window that step the track's resolves down to low quality. */
        const val DEGRADE_EVENTS = 3

        /** Stalls inside the window that freeze the track's in-place recovery retries. */
        const val FREEZE_EVENTS = 5
    }
}
