/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lastfm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/**
 * One song as the playback driver reports it. The host maps its own media model to this shape, so the scrobble rule
 * never depends on a platform type. [metadataDurationSeconds] is what the catalog reports, or 0 when it is unknown.
 */
data class LastFmPlay(
    val id: String,
    val artistNames: List<String>,
    val title: String,
    val albumTitle: String?,
    val metadataDurationSeconds: Int,
    val isPodcast: Boolean = false,
)

/**
 * Applies Last.fm's scrobble rule to one play at a time. It reports two events: [onNowPlaying] when a now-playing
 * update is due, and [onScrobblePoint] once the play has been heard long enough. The threshold is [timing].
 *
 * Listening time accumulates from the monotonic [clock] while the player reports that it is playing. Pauses and
 * buffering therefore do not count, and seeking forward cannot shortcut the threshold. Podcasts are never tracked.
 *
 * Confinement: the host calls every method, and runs [scope] on that same single thread. The state is not locked,
 * which matches the baseline's single player thread. The callbacks must return quickly. The host launches its own
 * network request from them and routes failures to the queue owner.
 */
class LastFmScrobbleTracker(
    private val scope: CoroutineScope,
    private val wallClockSeconds: () -> Long,
    private val onNowPlaying: (LastFmTrackMetadata) -> Unit,
    private val onScrobblePoint: (LastFmTrackMetadata, startedAtEpochSeconds: Long) -> Unit,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    /** The scrobble threshold. The defaults are the retained baseline values. */
    var timing: LastFmScrobbleTiming = LastFmScrobbleTiming()

    /** Whether now-playing updates are sent. Turning them off leaves the scrobble rule unchanged. */
    var useNowPlaying: Boolean = true

    private var job: Job? = null

    // Bumped on every reset, so a timer that fires after its play ended cannot scrobble the next play.
    private var generation = 0L
    private var current: LastFmPlay? = null
    private var currentStartedAtEpochSeconds = 0L
    private var durationSeconds = 0
    private var listenedMillis = 0L

    // A separate flag, not a zero sentinel: the monotonic clock may legitimately read zero near its origin.
    private var playing = false
    private var playingSinceMillis = 0L
    private var scrobbled = false

    // Null until a now-playing update has been sent for this play, so the first refresh is never throttled.
    private var nowPlayingSentAtMillis: Long? = null

    /** Ends the current play and cancels its pending scrobble. Call it when the host is torn down. */
    fun destroy() = reset()

    /**
     * A play starts. Starting the song that is already current only resumes it. [playerDurationMs] is the player's
     * duration once it is known, and it overrides the catalog duration.
     */
    fun onSongStart(
        play: LastFmPlay?,
        playerDurationMs: Long? = null,
    ) {
        if (play == null) return
        val now = nowMillis()
        if (current?.id == play.id) {
            updateDuration(playerDurationMs)
            resume(now)
            return
        }
        reset()
        if (play.isPodcast) return
        current = play
        currentStartedAtEpochSeconds = wallClockSeconds()
        durationSeconds = resolveDuration(play, playerDurationMs)
        playing = true
        playingSinceMillis = now
        schedule()
        if (useNowPlaying) {
            nowPlayingSentAtMillis = now
            emitNowPlaying(play)
        }
    }

    /** The same play resumes after a pause, and a now-playing refresh is sent when one is due. */
    fun onSongResume(play: LastFmPlay) {
        val active = current ?: return
        if (active.id != play.id) return
        if (resume(nowMillis())) emitNowPlaying(play)
    }

    /** The player paused. Listening time stops accumulating until the next resume. */
    fun onSongPause() = pause(nowMillis())

    /** The play ended or was replaced. Its pending scrobble is cancelled. */
    fun onSongStop() = reset()

    /** Routes a player state change to the start, resume or pause path. */
    fun onPlayerStateChanged(
        isPlaying: Boolean,
        play: LastFmPlay?,
        playerDurationMs: Long? = null,
    ) {
        if (play == null) return
        if (!isPlaying) {
            onSongPause()
            return
        }
        if (current?.id != play.id) {
            onSongStart(play, playerDurationMs)
        } else {
            updateDuration(playerDurationMs)
            onSongResume(play)
        }
    }

    /** Marks the play as playing and reports whether a now-playing refresh is due. */
    private fun resume(now: Long): Boolean {
        if (current == null) return false
        if (!playing) {
            playing = true
            playingSinceMillis = now
            schedule()
        }
        // Last.fm drops a now-playing status after the track's duration, so a resume refreshes it. The refresh is
        // throttled, so a rebuffering stall does not resend it every time.
        if (!useNowPlaying) return false
        val sentAt = nowPlayingSentAtMillis
        if (sentAt != null && now - sentAt < NOW_PLAYING_REFRESH_MILLIS) return false
        nowPlayingSentAtMillis = now
        return true
    }

    private fun pause(now: Long) {
        job?.cancel()
        job = null
        if (playing) {
            listenedMillis += now - playingSinceMillis
            playing = false
        }
    }

    private fun reset() {
        job?.cancel()
        job = null
        generation++
        current = null
        currentStartedAtEpochSeconds = 0L
        durationSeconds = 0
        listenedMillis = 0L
        playing = false
        playingSinceMillis = 0L
        scrobbled = false
        nowPlayingSentAtMillis = null
    }

    private fun updateDuration(playerDurationMs: Long?) {
        val play = current ?: return
        if (scrobbled) return
        val resolved = resolveDuration(play, playerDurationMs)
        if (resolved > 0 && resolved != durationSeconds) {
            durationSeconds = resolved
            if (playing) schedule()
        }
    }

    /** Arms the timer for the time still needed, counting the listening time already accumulated. */
    private fun schedule() {
        job?.cancel()
        job = null
        if (scrobbled || !playing) return
        val threshold = timing.thresholdMillis(durationSeconds) ?: return
        val heard = listenedMillis + (nowMillis() - playingSinceMillis)
        val remaining = (threshold - heard).coerceAtLeast(0L)
        val armedGeneration = generation
        job =
            scope.launch {
                delay(remaining)
                onThresholdReached(armedGeneration)
            }
    }

    private fun onThresholdReached(armedGeneration: Long) {
        if (armedGeneration != generation || scrobbled) return
        val play = current ?: return
        scrobbled = true
        job = null
        // A track Last.fm cannot name is not queued, as the baseline did.
        val track = trackFor(play) ?: return
        onScrobblePoint(track, currentStartedAtEpochSeconds)
    }

    private fun emitNowPlaying(play: LastFmPlay) {
        val track = trackFor(play) ?: return
        onNowPlaying(track)
    }

    private fun trackFor(play: LastFmPlay): LastFmTrackMetadata? =
        LastFmTrackMetadata.from(
            artistNames = play.artistNames,
            title = play.title,
            albumTitle = play.albumTitle,
            durationSeconds = durationSeconds,
        )

    private fun resolveDuration(
        play: LastFmPlay,
        playerDurationMs: Long?,
    ): Int {
        // The player reports a large negative value until the source is prepared, so only a positive value counts.
        val fromPlayer = playerDurationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        return fromPlayer ?: play.metadataDurationSeconds.coerceAtLeast(0)
    }

    private fun nowMillis(): Long = clock.nowNanos() / 1_000_000L

    private companion object {
        const val NOW_PLAYING_REFRESH_MILLIS = 60_000L
    }
}
