package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import moe.rukamori.archivetune.constants.MediaSessionConstants

/**
 * The -30/+30 s commands the notification shows on long content. Back clamps at zero; forward
 * clamps at the item end when the duration is known.
 */
internal fun handleSeekStepCustomCommand(player: Player, action: String) {
    when (action) {
        MediaSessionConstants.ACTION_SEEK_BACK_30 ->
            player.seekTo(
                (player.currentPosition - MediaSessionConstants.SEEK_STEP_30_MS).coerceAtLeast(0L),
            )
        MediaSessionConstants.ACTION_SEEK_FORWARD_30 -> {
            val durationMs = player.duration
            val targetMs = player.currentPosition + MediaSessionConstants.SEEK_STEP_30_MS
            player.seekTo(if (durationMs > 0L) targetMs.coerceAtMost(durationMs) else targetMs)
        }
    }
}
