package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlayerStyleAppleV8V9Test {
    @Test fun fixedBackgroundVolumeAndCanvasCapabilitiesHoldInBothOrientations() = runComposeUiTest {
        var style by mutableStateOf(PlayerDesignStyle.V8)
        var wide by mutableStateOf(false)
        val state=playerFixture().copy(canvasAvailable=true)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(if(wide) 1000.dp else 400.dp,if(wide) 600.dp else 740.dp)) {
                    BottomSheetPlayer(state,PlayerTestActions().callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name,
                            PlayerBackgroundStyleKey to PlayerBackgroundStyle.CUSTOM.name),playerSlots(),true)
                }
            }
        }
        for(landscape in listOf(false,true)) for(design in listOf(PlayerDesignStyle.V8,PlayerDesignStyle.V9,PlayerDesignStyle.APPLE_MUSIC)) {
            runOnIdle { style=design; wide=landscape }
            onNodeWithTag("player-${design.name}-${if(landscape) "landscape" else "portrait"}").assertExists()
            onNodeWithTag(if(design==PlayerDesignStyle.APPLE_MUSIC) "background-CUSTOM" else "background-DEFAULT").assertExists()
            if(design==PlayerDesignStyle.V9) onNodeWithTag("player-volume").assertDoesNotExist()
            else onNodeWithTag("player-volume").assertExists()
            if(design!=PlayerDesignStyle.V9) onNodeWithTag("canvas-true").assertExists()
        }
    }
}
