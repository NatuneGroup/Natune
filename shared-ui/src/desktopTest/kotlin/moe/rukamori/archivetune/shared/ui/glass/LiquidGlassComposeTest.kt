/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LiquidGlassComposeTest {
    @Test fun realGlassRolesHaveIdenticalFlatTwinBoundsAndHitTargets() = runComposeUiTest {
        var glass by mutableStateOf(true)
        var taps = 0
        setContent {
            MaterialTheme {
                BackdropScene(OpticalPolicy(glass, glass, true, false), Modifier.size(600.dp, 500.dp),
                    content = {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Red, Color.Blue))))
                    },
                    chrome = {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiquidGlassRole.entries.forEach { role ->
                                Box(Modifier.size(72.dp).clip(CircleShape).liquidGlassMaterial(shape = CircleShape, role = role)
                                    .clickable { taps++ }.testTag("glass-${role.name}"))
                            }
                        }
                    })
            }
        }
        val bounds = LiquidGlassRole.entries.associateWith { onNodeWithTag("glass-${it.name}").fetchSemanticsNode().boundsInRoot }
        onRoot().writeM4Screenshot("liquid-glass-comparison.png")
        LiquidGlassRole.entries.forEach { onNodeWithTag("glass-${it.name}").performClick() }
        assertEquals(5, taps)
        runOnIdle { glass = false }
        LiquidGlassRole.entries.forEach {
            assertEquals(bounds[it], onNodeWithTag("glass-${it.name}").fetchSemanticsNode().boundsInRoot)
            onNodeWithTag("glass-${it.name}").performClick()
        }
        assertEquals(10, taps)
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onRoot().writeM4Screenshot("liquid-glass-flat-twin.png")
    }
    @Test fun hazeArtworkRendersAndLiveOffKeepsItsExactFrame() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        setContent {
            MaterialTheme {
                ArtworkHaze(Modifier.size(240.dp).testTag("artwork-haze"), OpticalPolicy(false, false, enabled, false)) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Green, Color.Magenta))))
                }
            }
        }
        val before = onNodeWithTag("artwork-haze").fetchSemanticsNode().boundsInRoot
        onRoot().writeM4Screenshot("m4-artwork-haze.png")
        runOnIdle { enabled = false }
        assertEquals(before, onNodeWithTag("artwork-haze").fetchSemanticsNode().boundsInRoot)
        assertEquals(80.dp, ArtworkHazeStyle.blurRadius)
        assertEquals(.15f, ArtworkHazeStyle.noiseFactor)
    }
}
