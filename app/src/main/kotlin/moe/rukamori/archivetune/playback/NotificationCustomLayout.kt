package moe.rukamori.archivetune.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.domain.playback.PlaybackActionRestrictions
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRestrictionPolicy
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandSeekBack30
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandSeekForward30
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleLike
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleRepeatMode
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleShuffle
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleStartRadio

/** Items longer than this (podcasts, books) get -30/+30 s actions on the notification. */
private const val LONG_CONTENT_NOTIFICATION_MS = 300_000L
private const val SEEK_STEP_30_SECONDS = 30

/** How far into a restartable item "previous" may legitimately mean "play it again". */
private const val RESTART_PREVIOUS_POSITION_MS = 3_000L

/**
 * Session extras reserving notification slots for skip-to-previous and skip-to-next even when
 * the action is momentarily unavailable — the parity Apple's session sets on its own session.
 */
internal fun slotReservationSessionExtras(): Bundle =
    Bundle().apply {
        putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
        putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
    }

/**
 * The action restrictions for the current player snapshot, with the reason a control is
 * unavailable — the same verdict model surfaces could grey controls out against.
 */
internal fun playbackRestrictionsOf(player: Player): PlaybackActionRestrictions =
    PlaybackRestrictionPolicy.resolve(
        hasCurrentItem = player.currentMediaItem != null,
        hasSuccessor = player.hasNextMediaItem(),
        atQueueStart = !player.hasPreviousMediaItem(),
        canRestartCurrentItem =
            player.isCurrentMediaItemSeekable &&
                player.currentPosition > RESTART_PREVIOUS_POSITION_MS,
        currentItemSeekable = player.isCurrentMediaItemSeekable,
    )

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
    val restrictions = playbackRestrictionsOf(player)
    val seekStepButtons =
        if (player.duration != C.TIME_UNSET && player.duration > LONG_CONTENT_NOTIFICATION_MS) {
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(context.getString(R.string.seek_backward_dynamic, SEEK_STEP_30_SECONDS))
                    .setIconResId(R.drawable.flamingo_np_rewind)
                    .setSessionCommand(CommandSeekBack30)
                    .setEnabled(restrictions.seekWithin.allowed)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(context.getString(R.string.seek_forward_dynamic, SEEK_STEP_30_SECONDS))
                    .setIconResId(R.drawable.flamingo_np_fforward)
                    .setSessionCommand(CommandSeekForward30)
                    .setEnabled(restrictions.seekWithin.allowed)
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

/** True while a resumable playback notification should exist: an item is loaded and not idle or ended. */
internal fun hasResumablePlaybackNotification(player: Player): Boolean {
    val state = player.playbackState
    return player.mediaItemCount > 0 &&
        player.currentMediaItem != null &&
        state != Player.STATE_IDLE &&
        state != Player.STATE_ENDED
}

/**
 * Dedupes rebuilds of the notification's custom row. [buildNotificationCustomLayout] first runs while the
 * item still prepares, when duration is [C.TIME_UNSET] and seekability may still report false, so the
 * long-content seek pair can be missing — or linger on the next short item — until the signature below flips.
 * The service feeds its player listener through [onEvents]; a rebuild runs only when a relevant event changes
 * the signature [markBuilt] last recorded.
 */
@UnstableApi
internal class NotificationCustomLayoutGate {
    private var signature = SIGNATURE_UNBUILT

    /** Records what the just-built row shows, so later events only re-set on a real change. */
    fun markBuilt(player: Player) {
        signature = signatureOf(player)
    }

    /** Rebuilds through [rebuild] when a duration/seekability event flips the recorded signature. */
    fun onEvents(player: Player, events: Player.Events, rebuild: () -> Unit) {
        if (
            !events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
            )
        ) {
            return
        }
        val next = signatureOf(player)
        if (signature == SIGNATURE_UNBUILT) {
            signature = next
            return
        }
        if (next == signature) return
        signature = next
        rebuild()
    }

    private fun signatureOf(player: Player): Int =
        (if (player.duration != C.TIME_UNSET && player.duration > LONG_CONTENT_NOTIFICATION_MS) 1 else 0) or
            (if (player.isCurrentMediaItemSeekable) 2 else 0)

    private companion object {
        const val SIGNATURE_UNBUILT = -1
    }
}
