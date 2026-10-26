/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import moe.rukamori.archivetune.shared.ui.player.*
import kotlin.math.hypot

/** Look-local curves; the shell retains panel/back ownership while SpatialFlow owns its entrance. */
@Composable
internal fun SpatialFlowPanel(c: PlayerRenderContext,modifier: Modifier) {
    val isQueue=c.interaction.panel==PlayerPanel.QUEUE
    val reveal=remember(isQueue) { Animatable(if(c.motionActive) 0f else 1f) }
    LaunchedEffect(isQueue,c.motionActive) {
        if(c.motionActive) reveal.animateTo(1f,tween(if(isQueue) 240 else 420)) else reveal.snapTo(1f)
    }
    PlayerSidePanel(c,Modifier.graphicsLayer {
            if(isQueue) translationX=(1f-reveal.value)*size.width
            else { clip=true; shape=LyricsRevealShape(reveal.value) }
        }.then(modifier).testTag(if(isQueue) "spatial-queue-drawer" else "spatial-lyrics-reveal"))
}

private class LyricsRevealShape(private val fraction: Float): Shape {
    override fun createOutline(size: Size,layoutDirection: LayoutDirection,density: Density): Outline {
        val origin=androidx.compose.ui.geometry.Offset(size.width/2,size.height)
        val radius=hypot(size.width,size.height)*fraction.coerceIn(0f,1f)
        return Outline.Generic(Path().apply { addOval(Rect(origin.x-radius,origin.y-radius,origin.x+radius,origin.y+radius)) })
    }
}
