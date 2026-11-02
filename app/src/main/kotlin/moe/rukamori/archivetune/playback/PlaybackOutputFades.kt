package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
     * The session commands the service intercepts and re-drives itself so the edge gets a fade.
     * Seek commands carry an argument the callback cannot see, and STOP is destructive anyway —
     * those always pass through.
     */
    enum class Intercepted { PAUSE, PLAY }

    /** The intercepted action for a command, or null when the command may run unmodified. */
    fun interceptedCommand(command: Int, playWhenReady: Boolean): Intercepted? =
        when (command) {
            Player.COMMAND_PAUSE -> Intercepted.PAUSE
            Player.COMMAND_PLAY -> Intercepted.PLAY
            Player.COMMAND_PLAY_PAUSE -> if (playWhenReady) Intercepted.PAUSE else Intercepted.PLAY
            else -> null
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
 * Fades are skipped while a crossfade owns the volume or while casting, where `player.volume`
 * is a remote volume that would pay a network round-trip per step.
 */
internal class PlaybackOutputFades(
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val baseVolume: () -> Float,
    private val allowed: () -> Boolean,
) {
    private var job: Job? = null

    /** Ramps the live output to zero over [OutputFadePolicy.PAUSE_FADE_MS], then runs [pause]. */
    fun fadedPause(pause: () -> Unit) {
        val p = runCatching(player).getOrNull()
        if (p == null || !allowed()) {
            pause()
            return
        }
        job?.cancel()
        job =
            scope.launch {
                val start = p.volume
                repeat(OutputFadePolicy.FADE_STEPS) { step ->
                    if (!isActive) return@launch
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
        if (p == null || !allowed()) {
            play()
            return
        }
        job?.cancel()
        job =
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
        if (!allowed() || !p.isPlaying || !p.playWhenReady) return
        job?.cancel()
        job =
            scope.launch {
                val target = baseVolume().coerceIn(0f, 1f)
                p.volume = 0f
                fadeIn(p, target, OutputFadePolicy.SEEK_FADE_MS)
            }
    }

    private suspend fun fadeIn(p: Player, target: Float, durationMs: Long) {
        repeat(OutputFadePolicy.FADE_STEPS) { step ->
            if (!isActive) return
            p.volume = OutputFadePolicy.fadeInLevel(target, step)
            if (step + 1 < OutputFadePolicy.FADE_STEPS) {
                delay(durationMs / OutputFadePolicy.FADE_STEPS)
            }
        }
        p.volume = target
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
