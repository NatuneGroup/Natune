package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop player sheet renders only actions this host answers. [DESKTOP_PLAYER_ACTIONS] is derived from
 * [desktopPlayerAction], so an added branch is offered automatically and a removed one stops being offered; these
 * tests prove every offered action reaches exactly one handler and pin the actions that stay hidden.
 */
class DesktopPlayerActionsTest {
    @Test
    fun everyOfferedActionReachesOneHandler() {
        for (action in DESKTOP_PLAYER_ACTIONS) {
            val calls = mutableListOf<String>()
            val dispatched = desktopPlayerAction(
                action = action,
                occurrenceId = "occ",
                like = { calls += "like" },
                share = { calls += "share" },
                more = { calls += "more" },
                sleep = { calls += "sleep" },
                title = { calls += "title" },
                artist = { calls += "artist" },
            )
            when {
                action in DESKTOP_PLAYER_TRANSPORT_ACTIONS ->
                    assertFalse(dispatched, "$action is performed by the typed transport overrides")
                action == PlayerAction.COLLAPSE ->
                    assertTrue(dispatched, "the root collapses the sheet before an action reaches the host")
                else -> {
                    assertTrue(dispatched, "$action is offered but has no handler")
                    assertEquals(1, calls.size, "$action must reach exactly one handler")
                }
            }
        }
    }

    @Test
    fun theSheetHeaderActionsTheDesktopOwnsAreOffered() {
        assertTrue(PlayerAction.SHARE in DESKTOP_PLAYER_ACTIONS)
        assertTrue(PlayerAction.LIKE in DESKTOP_PLAYER_ACTIONS)
        assertTrue(PlayerAction.MORE in DESKTOP_PLAYER_ACTIONS)
        assertTrue(PlayerAction.SLEEP in DESKTOP_PLAYER_ACTIONS)
        assertTrue(PlayerAction.TITLE in DESKTOP_PLAYER_ACTIONS)
        assertTrue(PlayerAction.ARTIST in DESKTOP_PLAYER_ACTIONS)
    }

    @Test
    fun actionsWithoutADesktopOwnerAreNotOffered() {
        for (action in listOf(PlayerAction.OUTPUT)) {
            assertFalse(action in DESKTOP_PLAYER_ACTIONS, "$action has no desktop owner")
            assertFalse(desktopPlayerAction(action, "occ", {}, {}, {}, {}), "$action must not dispatch")
        }
    }
}
