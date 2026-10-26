/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MetrolistPlayerStyleTest {
    @Test fun modernVintageAndWidePanelHaveConnectedControlsAndLoading() = runComposeUiTest {
        var wide by mutableStateOf(false)
        var vintage by mutableStateOf(false)
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(if(wide) 1000.dp else 400.dp,if(wide) 600.dp else 740.dp)) {
                    BottomSheetPlayer(playerFixture().let { it.copy(playback=it.playback!!.copy(isBuffering=true)) },
                        callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to "METROLIST_KMP",
                            MetrolistPresentationKey to if(vintage) "VINTAGE" else "MODERN"),playerSlots(),true)
                }
            }
        }
        for(landscape in listOf(false,true)) for(oldLook in listOf(false,true)) {
            runOnIdle { wide=landscape; vintage=oldLook }
            onNodeWithTag("player-METROLIST_KMP-${if(landscape) "landscape" else "portrait"}").assertExists()
            onNodeWithTag("metrolist-${if(oldLook) "vintage" else "modern"}").assertExists()
            onNodeWithTag("metrolist-square-artwork").assertExists()
            onNodeWithTag("player-loading").assertExists()
            onNodeWithContentDescription("Loading").performScrollTo().performClick()
            for(label in listOf("Shuffle","Repeat","Sleep timer","More")) onNodeWithContentDescription(label).performScrollTo().performClick()
            if(landscape) {
                onNodeWithTag("lyrics-owner-slot").assertExists()
                onNodeWithTag("player-side-panel").assertExists()
            }
        }
        runOnIdle {
            assertEquals(4,callbacks.plays)
            assertEquals(4,callbacks.actions.count { it==PlayerAction.SHUFFLE })
            assertTrue(callbacks.visibility.contains(true))
        }
        val directory=File(requireNotNull(System.getProperty("archivetune.screenshotDir"))).apply { mkdirs() }
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File(directory,"player-style-metrolist-kmp.png")))
    }
}
