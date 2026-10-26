/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import moe.rukamori.archivetune.shared.ui.glass.ArtworkHaze

@Composable
internal fun PlayerBackground(c: PlayerRenderContext) {
    val p=c.preferences
    // The optical policy already folds in DisableBlur, platform blur support and surface visibility. One flag therefore
    // gates every blur below, and a hidden surface takes the gradient look without decoding or blurring its artwork.
    val blurActive=c.optical.artworkBlur
    val style=effectivePlayerBackground(p.style.resolveBackground(p.background),!blurActive)
    val palette=c.state.palette
    val base=MaterialTheme.colorScheme.background
    val accent=palette.getOrNull(0) ?: MaterialTheme.colorScheme.primary
    val second=palette.getOrNull(1) ?: MaterialTheme.colorScheme.secondary
    val last=palette.getOrNull(2) ?: base
    val flamingoMotion=p.style==moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle.FLAMINGO &&
        p.flamingoMotion && style==PlayerBackgroundStyle.BLUR
    val moving=(style==PlayerBackgroundStyle.GLOW_ANIMATED || flamingoMotion) && c.motionActive
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if(moving) {
            var previous=0L
            while(true) withFrameMillis { now ->
                if(previous!=0L) phase=(phase+((now-previous).coerceIn(0L,100L)/12000f))%1f
                previous=now
            }
        }
    }
    Box(Modifier.fillMaxSize().background(base).testTag("background-${style.name}")) {
        when(style) {
            PlayerBackgroundStyle.DEFAULT -> Unit
            PlayerBackgroundStyle.GRADIENT -> Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(accent.copy(alpha=.85f),second.copy(alpha=.65f),last))))
            PlayerBackgroundStyle.COLORING -> Box(Modifier.fillMaxSize().background(accent.copy(alpha=.65f)))
            PlayerBackgroundStyle.CUSTOM -> {
                val contrast=p.customContrast
                val brightness=(p.customBrightness-1f)*255f
                val offset=(1f-contrast)*127.5f+brightness
                val filter=ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                    contrast,0f,0f,0f,offset, 0f,contrast,0f,0f,offset,
                    0f,0f,contrast,0f,offset, 0f,0f,0f,1f,0f,
                )))
                val paint=remember(filter) { Paint().apply { colorFilter=filter } }
                c.slots.customBackground(p.customImageUri,Modifier.fillMaxSize()
                    .then(if(blurActive) Modifier.blur(p.customBlur.dp) else Modifier)
                    .drawWithContent {
                        drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero,size),paint) }
                        drawContent()
                        drawIntoCanvas { it.restore() }
                    })
            }
            PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.BLUR_GRADIENT -> {
                if(blurActive) ArtworkHaze(
                    modifier=Modifier.fillMaxSize(),
                    policy=c.optical,
                ) {
                    c.slots.artwork(c.state.playback?.occurrenceId,Modifier.fillMaxSize().graphicsLayer {
                        if(flamingoMotion) {
                            scaleX=1.1f; scaleY=1.1f
                            translationX=sin(phase*6.283185f)*size.width*.025f
                            translationY=cos(phase*6.283185f)*size.height*.025f
                        }
                    })
                }
                if(style==PlayerBackgroundStyle.BLUR_GRADIENT) Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(accent.copy(alpha=.4f),base))))
            }
            PlayerBackgroundStyle.GLOW, PlayerBackgroundStyle.GLOW_ANIMATED -> Canvas(Modifier.fillMaxSize()) {
                val t=phase*6.283185f
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha=.7f),accent.copy(alpha=0f)),
                    Offset(size.width*(.3f+.15f*sin(t)),size.height*(.3f+.15f*cos(t))),size.maxDimension*.8f))
                drawRect(Brush.radialGradient(listOf(second.copy(alpha=.6f),second.copy(alpha=0f)),
                    Offset(size.width*.8f,size.height*.8f),size.maxDimension*.7f))
            }
        }
    }
}
