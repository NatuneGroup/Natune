package moe.rukamori.archivetune.playback

import android.os.SystemClock

/**
 * Counts the rebuffer stalls one track suffers and degrades or freezes it when they come too
 * fast, the way Spotify steps down instead of spinning on a saturated link. A stall counts only
 * after the track reached READY once — the initial-buffer watchdog owns the earlier phase — and
 * only while playback was wanted; a stall shorter than [MIN_STALL_MS] or one opened within
 * [SEEK_TAG_MS] of a user seek is a seek flicker, not a rebuffer. Pausing closes a stall where it
 * stands — its pre-pause length counts when it already qualifies — and resuming while still
 * BUFFERING opens a fresh timer, so paused time never inflates a stall's duration. Once
 * [DEGRADE_EVENTS] stalls land inside [WINDOW_MS], re-resolves for the track ask for low quality;
 * once [FREEZE_EVENTS] land, in-place recovery retries stop so a dead stream cannot loop
 * re-prepares. Both decisions fade with the window: a track that then plays cleanly earns its
 * normal posture back, and a media transition always starts a fresh count.
 *
 * Mutators run on the main thread with the listener callbacks. Queries may run on any thread:
 * they read only the immutable [snapshot] swapped wholesale after every mutation.
 */
internal class RebufferMonitor(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Everything a query needs, immutable. Never publish partially-updated pieces. */
    private data class Snapshot(
        val mediaId: String? = null,
        val stallOpenedAtMs: Long? = null,
        val stallFromSeek: Boolean = false,
        val stalls: List<Long> = emptyList(),
    )

    @Volatile
    private var snapshot = Snapshot()

    private var mediaId: String? = null
    private var sawReady = false
    private var bufferingNow = false

    /** Start time of the stall still open, or null; tagged when it followed a user seek. */
    private var stallOpenedAtMs: Long? = null
    private var stallFromSeek = false

    /** The most recent user seek; a stall opening within [SEEK_TAG_MS] of it is seek-originated. */
    private var lastSeekAtMs: Long = Long.MIN_VALUE

    /** Start times of counted stalls still inside the window. */
    private val stalls = ArrayDeque<Long>()

    /** The id the monitor currently tracks; a stale recovery request checks it before superseding. */
    fun currentMediaId(): String? = snapshot.mediaId

    /**
     * A media transition — even onto the same id again, as a repeat or duplicate occurrence —
     * starts a fresh count. A gapless AUTO transition can land without ever leaving READY, so the
     * state callback alone cannot be trusted to seed the new track: [ready] and [buffering] carry
     * the state the player is already in, or the new track's first genuine stall would never
     * count at all.
     */
    fun onMediaItemChanged(mediaId: String?, ready: Boolean, buffering: Boolean) {
        reset(mediaId, sawReady = ready, bufferingNow = buffering)
        publish()
    }

    /**
     * A user seek closes whatever stall was open — a stall interrupted by intent is real until
     * then — and marks the next one: the BUFFERING a seek forces is user intent catching up, not
     * evidence the link cannot keep up.
     */
    fun onSeek() {
        val now = nowMs()
        closeStall(now)
        lastSeekAtMs = now
        publish()
    }

    /**
     * Pausing ends a stall where it stands: its pre-pause length still counts when it already
     * qualified, and the paused stretch is never part of a later stall's duration. Resuming while
     * the track is still BUFFERING opens a fresh timer — the rebuffer continues, only the pause
     * itself is excluded.
     */
    fun onPlayWhenReadyChanged(playing: Boolean) {
        val now = nowMs()
        if (playing) {
            if (bufferingNow && sawReady && stallOpenedAtMs == null) openStall(now)
        } else {
            closeStall(now)
        }
        publish()
    }

    /**
     * Feed every session-player state change: [buffering] and [ready] are the state the player
     * just entered, [playWhenReady] its intent. A [mediaId] different from the tracked one resets
     * the window so a stall streak can never leak onto the next track.
     */
    fun onPlaybackStateChanged(mediaId: String?, buffering: Boolean, ready: Boolean, playWhenReady: Boolean) {
        if (mediaId != this.mediaId) reset(mediaId, sawReady = ready, bufferingNow = buffering)
        bufferingNow = buffering
        val now = nowMs()
        when {
            buffering -> if (playWhenReady && sawReady && stallOpenedAtMs == null) openStall(now)
            else -> {
                if (ready) sawReady = true
                closeStall(now)
            }
        }
        prune(now)
        publish()
    }

    /** True while the track's stall count asks for a low-quality re-resolve. */
    fun shouldStepDownQuality(mediaId: String): Boolean =
        stallCount(snapshot, nowMs(), mediaId) >= DEGRADE_EVENTS

    /** True while the track's stall count says in-place retries must stop entirely. */
    fun shouldSuppressRetries(mediaId: String): Boolean =
        stallCount(snapshot, nowMs(), mediaId) >= FREEZE_EVENTS

    private fun publish() {
        snapshot = Snapshot(mediaId, stallOpenedAtMs, stallFromSeek, stalls.toList())
    }

    private fun stallCount(s: Snapshot, now: Long, mediaId: String): Int {
        if (s.mediaId != mediaId) return 0
        var count = s.stalls.count { now - it < WINDOW_MS }
        val opened = s.stallOpenedAtMs
        if (opened != null && !s.stallFromSeek && now - opened in MIN_STALL_MS until WINDOW_MS) count++
        return count
    }

    private fun openStall(now: Long) {
        stallOpenedAtMs = now
        stallFromSeek = lastSeekAtMs != Long.MIN_VALUE && now - lastSeekAtMs < SEEK_TAG_MS
    }

    private fun closeStall(now: Long) {
        val openedAt = stallOpenedAtMs ?: return
        stallOpenedAtMs = null
        val fromSeek = stallFromSeek
        stallFromSeek = false
        if (!fromSeek && now - openedAt >= MIN_STALL_MS) stalls.addLast(openedAt)
    }

    private fun prune(now: Long) {
        while (stalls.isNotEmpty() && now - stalls.first() >= WINDOW_MS) stalls.removeFirst()
    }

    private fun reset(newMediaId: String?, sawReady: Boolean = false, bufferingNow: Boolean = false) {
        mediaId = newMediaId
        this.sawReady = sawReady
        this.bufferingNow = bufferingNow
        stallOpenedAtMs = null
        stallFromSeek = false
        lastSeekAtMs = Long.MIN_VALUE
        stalls.clear()
    }

    private companion object {
        /** The stretch a stall streak must fit in before it counts against the track. */
        const val WINDOW_MS = 90_000L

        /** A buffering spell shorter than this is a seek or tickle, never a rebuffer. */
        const val MIN_STALL_MS = 1_500L

        /** A stall opening this soon after a user seek belongs to the seek, not the link. */
        const val SEEK_TAG_MS = 2_000L

        /** Stalls inside the window that step the track's resolves down to low quality. */
        const val DEGRADE_EVENTS = 3

        /** Stalls inside the window that freeze the track's in-place recovery retries. */
        const val FREEZE_EVENTS = 5
    }
}
