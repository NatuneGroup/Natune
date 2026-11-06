package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_like
import moe.rukamori.archivetune.shared.ui.resources.m5_more
import moe.rukamori.archivetune.shared.ui.resources.m5_output
import moe.rukamori.archivetune.shared.ui.resources.m5_share
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The window renders the player sheet's header actions only where the host has an owner for them. The desktop host
 * derives its support set from its own action dispatch (`desktopPlayerAction`), so the same set this test pins here is
 * the one that host offers; the desktop-side test proves every action in it reaches a handler.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerHeaderActionSupportTest {
    /** The actions the desktop sheet header owns: share, like and more, exactly as its dispatch answers them. */
    private val desktop = PlayerActionSupport(
        listOf(PlayerAction.SHARE, PlayerAction.LIKE, PlayerAction.MORE),
    )

    @Test
    fun everyHeaderActionTheHostAnswersDispatchesItsOwnAction() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        var share = ""
        var like = ""
        var more = ""
        setContent {
            share = stringResource(Res.string.m5_share)
            like = stringResource(Res.string.m5_like)
            more = stringResource(Res.string.m5_more)
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(
                        playerFixture().copy(actionSupport = desktop),
                        callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.V4.name),
                        playerSlots(),
                        true,
                    )
                }
            }
        }
        onNodeWithContentDescription(share).performClick()
        onNodeWithContentDescription(like).performClick()
        onNodeWithContentDescription(more).performClick()
        runOnIdle {
            assertEquals(listOf(PlayerAction.SHARE, PlayerAction.LIKE, PlayerAction.MORE), callbacks.actions)
        }
    }

    @Test
    fun anActionTheHostDoesNotAnswerIsNotDrawn() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        var share = ""
        var more = ""
        setContent {
            share = stringResource(Res.string.m5_share)
            more = stringResource(Res.string.m5_more)
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(
                        playerFixture().copy(actionSupport = PlayerActionSupport(listOf(PlayerAction.SHARE))),
                        callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.V4.name),
                        playerSlots(),
                        true,
                    )
                }
            }
        }
        onNodeWithContentDescription(share).performClick()
        onNodeWithContentDescription(more).assertDoesNotExist()
        runOnIdle { assertEquals(listOf(PlayerAction.SHARE), callbacks.actions) }
    }

    @Test
    fun aHostWithoutAnOutputPickerShowsNoOutputControl() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        var output = ""
        setContent {
            output = stringResource(Res.string.m5_output)
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(
                        playerFixture().copy(actionSupport = desktop),
                        callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name),
                        playerSlots(),
                        true,
                    )
                }
            }
        }
        onNodeWithContentDescription(output).assertDoesNotExist()
    }

    @Test
    fun aHostWithoutASongRouteLeavesTheTitleAsPlainText() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(
                        playerFixture().copy(actionSupport = desktop),
                        callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.V4.name),
                        playerSlots(),
                        true,
                    )
                }
            }
        }
        // A tap only reaches TITLE where the host opens the song; here the text carries no click action at all.
        assertFalse(onNodeWithText("Test song").fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        onNodeWithText("Test song").assertExists()
        runOnIdle { assertEquals(emptyList<PlayerAction>(), callbacks.actions) }
    }
}
