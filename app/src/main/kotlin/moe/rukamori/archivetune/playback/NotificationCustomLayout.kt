package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandSeekBack30
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandSeekForward30
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleLike
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleRepeatMode
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleShuffle
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleStartRadio

/** Items longer than this (podcasts, books) get -30/+30 s actions on the notification. */
private const val LONG_CONTENT_NOTIFICATION_MS = 300_000L
private const val SEEK_STEP_30_SECONDS = 30

/**
 * The notification's custom action row. Past [LONG_CONTENT_NOTIFICATION_MS] the row leads with the
 * -30/+30 s seek actions podcast and book listeners want, ahead of the ordinary toggles — Apple
 * Music's notification swaps to the same seek pair for long content.
 */
@UnstableApi
internal fun buildNotificationCustomLayout(
    context: Context,
    player: Player,
    liked: Boolean,
    hasSong: Boolean,
): List<CommandButton> {
    val seekStepButtons =
        if (player.duration != C.TIME_UNSET && player.duration > LONG_CONTENT_NOTIFICATION_MS) {
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(context.getString(R.string.seek_backward_dynamic, SEEK_STEP_30_SECONDS))
                    .setIconResId(R.drawable.flamingo_np_rewind)
                    .setSessionCommand(CommandSeekBack30)
                    .setEnabled(player.isCurrentMediaItemSeekable)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(context.getString(R.string.seek_forward_dynamic, SEEK_STEP_30_SECONDS))
                    .setIconResId(R.drawable.flamingo_np_fforward)
                    .setSessionCommand(CommandSeekForward30)
                    .setEnabled(player.isCurrentMediaItemSeekable)
                    .build(),
            )
        } else {
            emptyList()
        }
    return seekStepButtons +
        listOf(
            CommandButton
                .Builder()
                .setDisplayName(
                    context.getString(
                        if (liked) {
                            R.string.action_remove_like
                        } else {
                            R.string.action_like
                        },
                    ),
                ).setIconResId(if (liked) R.drawable.favorite else R.drawable.favorite_border)
                .setSessionCommand(CommandToggleLike)
                .setEnabled(hasSong)
                .build(),
            CommandButton
                .Builder()
                .setDisplayName(
                    context.getString(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.string.repeat_mode_off
                            REPEAT_MODE_ONE -> R.string.repeat_mode_one
                            REPEAT_MODE_ALL -> R.string.repeat_mode_all
                            else -> R.string.repeat_mode_off
                        },
                    ),
                ).setIconResId(
                    when (player.repeatMode) {
                        REPEAT_MODE_OFF -> R.drawable.repeat
                        REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                        REPEAT_MODE_ALL -> R.drawable.repeat_on
                        else -> R.drawable.repeat
                    },
                ).setSessionCommand(CommandToggleRepeatMode)
                .build(),
            CommandButton
                .Builder()
                .setDisplayName(context.getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                .setSessionCommand(CommandToggleShuffle)
                .build(),
            CommandButton
                .Builder()
                .setDisplayName(context.getString(R.string.start_radio))
                .setIconResId(R.drawable.radio)
                .setSessionCommand(CommandToggleStartRadio)
                .setEnabled(hasSong)
                .build(),
        )
}
