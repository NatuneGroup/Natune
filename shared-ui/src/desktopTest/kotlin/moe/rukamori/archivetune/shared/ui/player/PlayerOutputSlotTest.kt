package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_output
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class PlayerOutputSlotTest {
    @Test
    fun suppliedOutputControlSurvivesTheHandoffWrapperAndOwnsTheTap() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        var taps = 0
        setContent {
            MaterialTheme {
                val base = playerSlots()
                val slots = PlayerSlots(base.artwork, base.canvas, base.customBackground, base.lyrics,
                    base.compactLyrics, base.queue, outputButton = { modifier, _, enabled ->
                        Box(modifier.testTag("host-output").clickable(enabled = enabled) { taps++ })
                    })
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture(), callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name), slots, true)
                }
            }
        }
        onNodeWithTag("host-output").assertExists().performClick()
        runOnIdle {
            assertEquals(1, taps)
            assertEquals(emptyList(), callbacks.actions)
        }
    }

    @Test
    fun hostsWithoutANativeControlKeepTheirOutputAction() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        var label = ""
        setContent {
            label = stringResource(Res.string.m5_output)
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture(), callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name), playerSlots(), true)
                }
            }
        }
        onNodeWithContentDescription(label).performClick()
        runOnIdle { assertEquals(listOf(PlayerAction.OUTPUT), callbacks.actions) }
    }

    @Test
    fun handoffDoesNotReplaceTheHostsNativeOutputControl() {
        val output: @Composable (Modifier, Color, Boolean) -> Unit = { _, _, _ -> }
        val base = playerSlots()
        val slots = PlayerSlots(base.artwork, base.canvas, base.customBackground, base.lyrics,
            base.compactLyrics, base.queue, outputButton = output)
        assertSame(output, slots.withHandoff(PlayerHandoffDisplay(null) { 0f }).outputButton)
    }
}
