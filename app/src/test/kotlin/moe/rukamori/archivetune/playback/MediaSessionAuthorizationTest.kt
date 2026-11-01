package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.constants.MediaSessionConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onConnect authorization policy: trusted/system controllers keep the full custom command
 * surface, the notification shelf keeps shuffle/repeat/like but not the library toggle, and
 * untiered apps get a transport-only player surface.
 */
class MediaSessionAuthorizationTest {
    private fun tier(
        packageName: String? = null,
        uid: Int = 10_000,
        appUid: Int = 10_000,
        trusted: Boolean = false,
        listener: Boolean = false,
        assistant: Boolean = false,
    ) = MediaSessionAuthorization.tierOf(
        packageName = packageName,
        uid = uid,
        appUid = appUid,
        isSystemTrusted = trusted,
        isNotificationListener = listener,
        isAssistant = assistant,
    )

    @Test
    fun systemTrusted_isPrivileged() {
        assertEquals(MediaControllerTier.PRIVILEGED, tier(trusted = true))
    }

    @Test
    fun rootUid_isPrivileged() {
        assertEquals(MediaControllerTier.PRIVILEGED, tier(uid = 0))
    }

    @Test
    fun gearheadPackage_isPrivileged() {
        assertEquals(
            MediaControllerTier.PRIVILEGED,
            tier(packageName = MediaSessionAuthorization.GEARHEAD_PACKAGE, uid = 20_000),
        )
    }

    @Test
    fun systemUiPackage_isNotification() {
        assertEquals(
            MediaControllerTier.NOTIFICATION,
            tier(packageName = MediaSessionAuthorization.SYSTEMUI_PACKAGE, uid = 20_000),
        )
    }

    @Test
    fun enabledNotificationListener_isNotification() {
        assertEquals(MediaControllerTier.NOTIFICATION, tier(uid = 20_000, listener = true))
    }

    @Test
    fun ownUid_isSameUid() {
        assertEquals(MediaControllerTier.SAME_UID, tier(uid = 10_000, appUid = 10_000))
    }

    @Test
    fun configuredAssistant_isAssistant() {
        assertEquals(MediaControllerTier.ASSISTANT, tier(uid = 20_000, assistant = true))
    }

    @Test
    fun arbitraryPackage_isUntiered() {
        assertEquals(MediaControllerTier.UNTIERED, tier(packageName = "com.example.other", uid = 20_000))
    }

    @Test
    fun privilegedAndSameUid_getEveryCustomCommand() {
        // Only the const-val action names are safe to reference in a plain JVM test: the
        // SessionCommand/Bundle values would fail to class-initialise outside Android.
        val all =
            setOf(
                MediaSessionConstants.ACTION_TOGGLE_LIKE,
                MediaSessionConstants.ACTION_TOGGLE_START_RADIO,
                MediaSessionConstants.ACTION_TOGGLE_LIBRARY,
                MediaSessionConstants.ACTION_TOGGLE_SHUFFLE,
                MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE,
                MediaSessionConstants.ACTION_SEEK_BACK_30,
                MediaSessionConstants.ACTION_SEEK_FORWARD_30,
            )
        assertEquals(all, MediaSessionAuthorization.ALL_CUSTOM_ACTIONS)
        assertEquals(all, MediaSessionAuthorization.allowedCustomActions(MediaControllerTier.PRIVILEGED))
        assertEquals(all, MediaSessionAuthorization.allowedCustomActions(MediaControllerTier.SAME_UID))
    }

    @Test
    fun notificationTier_keepsNotificationCommandsButNotLibrary() {
        val allowed = MediaSessionAuthorization.allowedCustomActions(MediaControllerTier.NOTIFICATION)
        assertTrue(MediaSessionConstants.ACTION_TOGGLE_LIKE in allowed)
        assertTrue(MediaSessionConstants.ACTION_TOGGLE_START_RADIO in allowed)
        assertTrue(MediaSessionConstants.ACTION_TOGGLE_SHUFFLE in allowed)
        assertTrue(MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE in allowed)
        assertFalse(MediaSessionConstants.ACTION_TOGGLE_LIBRARY in allowed)
    }

    @Test
    fun assistantAndUntiered_getNoCustomCommands() {
        assertTrue(MediaSessionAuthorization.allowedCustomActions(MediaControllerTier.ASSISTANT).isEmpty())
        assertTrue(MediaSessionAuthorization.allowedCustomActions(MediaControllerTier.UNTIERED).isEmpty())
    }

    @Test
    fun onlyUntiered_isTransportRestricted() {
        for (tier in MediaControllerTier.entries) {
            val expected =
                if (tier == MediaControllerTier.UNTIERED) {
                    PlayerCommandAccess.TRANSPORT
                } else {
                    PlayerCommandAccess.FULL
                }
            assertEquals(expected, MediaSessionAuthorization.playerCommandAccess(tier))
        }
    }

    @Test
    fun transportSubset_excludesQueueAndModeMutations() {
        // The transport subset must not let an arbitrary app reshape the queue or repeat/shuffle
        // state; it only carries play/pause, seeks and read-only getters.
        val banned =
            setOf(
                androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM,
                androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS,
                androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE,
                androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE,
                androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH,
                androidx.media3.common.Player.COMMAND_SET_VOLUME,
            )
        assertTrue(MediaSessionAuthorization.MINIMAL_PLAYER_COMMANDS.intersect(banned).isEmpty())
    }

    @Test
    fun guestState_blocksEveryMutatingCustomCommand() {
        for (action in MediaSessionAuthorization.ALL_CUSTOM_ACTIONS) {
            assertTrue(
                MediaSessionAuthorization.isCustomCommandBlocked(action, isListenTogetherGuest = true),
            )
        }
    }

    @Test
    fun hostOrAbsentRoom_leavesCustomCommandsOpen() {
        for (action in MediaSessionAuthorization.ALL_CUSTOM_ACTIONS) {
            assertFalse(
                MediaSessionAuthorization.isCustomCommandBlocked(action, isListenTogetherGuest = false),
            )
        }
        assertFalse(MediaSessionAuthorization.isCustomCommandBlocked(null, isListenTogetherGuest = true))
    }
}
