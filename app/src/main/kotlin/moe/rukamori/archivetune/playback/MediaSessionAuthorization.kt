package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import moe.rukamori.archivetune.constants.MediaSessionConstants

/**
 * Trust level a connecting [androidx.media3.session.MediaSession.ControllerInfo] is sorted into
 * before its session and player command sets are built.
 */
enum class MediaControllerTier {
    /** System-trusted browsers, root and the Android Auto projection host. */
    PRIVILEGED,

    /** The notification shelf: SystemUI and enabled notification listeners. */
    NOTIFICATION,

    /** Our own process (in-app controllers, widgets, the notification provider). */
    SAME_UID,

    /** The device's configured assistant / voice interaction service. */
    ASSISTANT,

    /** Every other app. */
    UNTIERED,
}

/**
 * How much of the player's command surface a controller may drive. Read-only metadata and plain
 * transport stay available to every connected controller; queue- and mode-mutating commands are
 * withheld from [MediaControllerTier.UNTIERED] apps.
 */
enum class PlayerCommandAccess {
    FULL,
    TRANSPORT,
}

/**
 * Per-controller command authorization, mirroring the policy notification clients rely on:
 * SystemUI and other trusted controllers keep the shuffle/repeat/like custom commands while
 * arbitrary apps only see the base session commands and a transport-level player surface.
 */
object MediaSessionAuthorization {
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    private val NOTIFICATION_CUSTOM_ACTIONS =
        setOf(
            MediaSessionConstants.ACTION_TOGGLE_LIKE,
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO,
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE,
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE,
        )

    val ALL_CUSTOM_ACTIONS: Set<String> =
        NOTIFICATION_CUSTOM_ACTIONS + MediaSessionConstants.ACTION_TOGGLE_LIBRARY

    val MINIMAL_PLAYER_COMMANDS: Set<Int> =
        setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS,
        )

    fun tierOf(
        packageName: String?,
        uid: Int,
        appUid: Int,
        isSystemTrusted: Boolean,
        isNotificationListener: Boolean,
        isAssistant: Boolean,
    ): MediaControllerTier =
        when {
            isSystemTrusted || uid == 0 || packageName == GEARHEAD_PACKAGE -> MediaControllerTier.PRIVILEGED
            packageName == SYSTEMUI_PACKAGE || isNotificationListener -> MediaControllerTier.NOTIFICATION
            uid == appUid -> MediaControllerTier.SAME_UID
            isAssistant -> MediaControllerTier.ASSISTANT
            else -> MediaControllerTier.UNTIERED
        }

    fun allowedCustomActions(tier: MediaControllerTier): Set<String> =
        when (tier) {
            MediaControllerTier.PRIVILEGED,
            MediaControllerTier.SAME_UID,
            -> ALL_CUSTOM_ACTIONS

            MediaControllerTier.NOTIFICATION -> NOTIFICATION_CUSTOM_ACTIONS
            MediaControllerTier.ASSISTANT,
            MediaControllerTier.UNTIERED,
            -> emptySet()
        }

    fun playerCommandAccess(tier: MediaControllerTier): PlayerCommandAccess =
        if (tier == MediaControllerTier.UNTIERED) {
            PlayerCommandAccess.TRANSPORT
        } else {
            PlayerCommandAccess.FULL
        }

    /**
     * While this device is a Listen Together guest, every mutating custom command is rejected so
     * a notification or car control cannot silently change the host's queue state.
     */
    fun isCustomCommandBlocked(
        action: String?,
        isListenTogetherGuest: Boolean,
    ): Boolean = isListenTogetherGuest && action != null && action in ALL_CUSTOM_ACTIONS
}
