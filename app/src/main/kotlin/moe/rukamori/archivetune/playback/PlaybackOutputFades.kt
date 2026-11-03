package moe.rukamori.archivetune.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Pure rules for the short output fades that keep pause, resume and seek edges click-free —
 * the analogue of Spotify's pause/flush fade keys. A pause ramps the current output level to
 * zero before the player actually stops; a resume starts at zero and climbs back; a seek dips
 * the landing. All ramps are linear volume steps, short enough that no one hears a fade.
 */
object OutputFadePolicy {
    const val PAUSE_FADE_MS = 64L
    const val RESUME_FADE_MS = 80L
    const val SEEK_FADE_MS = 80L
    const val FADE_STEPS = 8

    /**
     * A transport edge the fades smooth. The session-facing player wrapper reports the intent
     * directly from the real call — pause(), play() or setPlayWhenReady() — because Media3
     * resolves every controller transport request into exactly one of those on session.player;
     * inferring it from current state gets repeated toggles wrong.
     */
    enum class Edge { PAUSE, PLAY }

    /**
     * True when [edge] crosses a live/muted boundary worth ramping: a pause only has an
     * audible edge while output is live, a play only while output is muted. Redundant calls
     * forward straight through so a stale-race no-op never dips the level.
     */
    fun hasAudibleEdge(edge: Edge, playWhenReady: Boolean): Boolean =
        when (edge) {
            Edge.PAUSE -> playWhenReady
            Edge.PLAY -> !playWhenReady
        }

    /** Linear output level at [step] of [steps] when fading down from [startLevel]. */
    fun fadeOutLevel(startLevel: Float, step: Int, steps: Int = FADE_STEPS): Float =
        (startLevel * (1f - (step + 1) / steps.toFloat())).coerceIn(0f, 1f)

    /** Linear output level at [step] of [steps] when fading up toward [targetLevel]. */
    fun fadeInLevel(targetLevel: Float, step: Int, steps: Int = FADE_STEPS): Float =
        (targetLevel * (step + 1) / steps.toFloat()).coerceIn(0f, 1f)
}

/**
 * Owns the output-fade ramps. Writes [Player.volume] directly rather than going through the
 * effective-volume factors: the dips are 64–80 ms, far shorter than the effective ramp's own
 * minimum, and every ramp ends by restoring the honest effective level.
 *
 * Two jobs, deliberately separate: [actionJob] carries a pending transport intent (the
 * pause/play call itself lands inside it) so the latest request always wins by cancelling its
 * predecessor; [dipJob] is a purely decorative seek dip that never carries an intent — it can
 * never cancel a pending pause, so a seek inside the 64 ms pause fade can no longer keep the
 * player (and audio-focus-loss pauses) alive.
 *
 * Fades are skipped while a crossfade owns the volume or while casting, where `player.volume`
 * is a remote volume that would pay a network round-trip per step.
 */
internal class PlaybackOutputFades(
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val baseVolume: () -> Float,
    private val allowed: () -> Boolean,
) {
    private var actionJob: Job? = null
    private var dipJob: Job? = null

    /** Ramps the live output to zero over [OutputFadePolicy.PAUSE_FADE_MS], then runs [pause]. */
    fun fadedPause(pause: () -> Unit) {
        val p = runCatching(player).getOrNull()
        actionJob?.cancel()
        actionJob = null
        dipJob?.cancel()
        dipJob = null
        if (p == null ||
            !allowed() ||
            !OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PAUSE, p.playWhenReady)
        ) {
            pause()
            return
        }
        actionJob =
            scope.launch {
                val start = p.volume
                repeat(OutputFadePolicy.FADE_STEPS) { step ->
                    ensureActive()
                    p.volume = OutputFadePolicy.fadeOutLevel(start, step)
                    delay(OutputFadePolicy.PAUSE_FADE_MS / OutputFadePolicy.FADE_STEPS)
                }
                pause()
                // Restore the honest level while paused so an un-faded resume path never
                // wakes up muted; intercepted resumes re-mute themselves before playing.
                p.volume = baseVolume().coerceIn(0f, 1f)
            }
    }

    /** Plays at zero output and ramps up over [OutputFadePolicy.RESUME_FADE_MS]. */
    fun mutedPlay(play: () -> Unit) {
        val p = runCatching(player).getOrNull()
        actionJob?.cancel()
        actionJob = null
        dipJob?.cancel()
        dipJob = null
        if (p == null ||
            !allowed() ||
            !OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PLAY, p.playWhenReady)
        ) {
            play()
            return
        }
        actionJob =
            scope.launch {
                val target = baseVolume().coerceIn(0f, 1f)
                p.volume = 0f
                play()
                fadeIn(p, target, OutputFadePolicy.RESUME_FADE_MS)
            }
    }

    /** Dips the landing of a seek that already happened while audio keeps flowing. */
    fun dipForSeek() {
        val p = runCatching(player).getOrNull() ?: return
        // A pending transport edge owns the volume — and it must land regardless of the seek.
        if (actionJob?.isActive == true) return
        if (!allowed() || !p.isPlaying || !p.playWhenReady) return
        dipJob?.cancel()
        dipJob =
            scope.launch {
                val target = baseVolume().coerceIn(0f, 1f)
                p.volume = 0f
                fadeIn(p, target, OutputFadePolicy.SEEK_FADE_MS)
            }
    }

    private suspend fun fadeIn(p: Player, target: Float, durationMs: Long) {
        repeat(OutputFadePolicy.FADE_STEPS) { step ->
            coroutineContext.ensureActive()
            p.volume = OutputFadePolicy.fadeInLevel(target, step)
            if (step + 1 < OutputFadePolicy.FADE_STEPS) {
                delay(durationMs / OutputFadePolicy.FADE_STEPS)
            }
        }
        p.volume = target
    }

    fun cancel() {
        actionJob?.cancel()
        actionJob = null
        dipJob?.cancel()
        dipJob = null
    }
}

/**
 * Session-facing player wrapper: the session invokes transport calls on [target] through this
 * instance, so the true intent is known per call — pause() means pause, play() means play and
 * setPlayWhenReady() carries the flag — instead of being guessed from state the way a session
 * command handler has to. The requested call still runs on [target] after (or without) its
 * fade, so Media3 preparation and playback-resumption processing stay untouched.
 */
@OptIn(UnstableApi::class)
internal class OutputFadingPlayer(
    private val target: Player,
    private val fades: PlaybackOutputFades,
) : ForwardingPlayer(target) {
    override fun pause() = fades.fadedPause { target.pause() }

    override fun play() = fades.mutedPlay { target.play() }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            fades.mutedPlay { target.setPlayWhenReady(true) }
        } else {
            fades.fadedPause { target.setPlayWhenReady(false) }
        }
    }
}
