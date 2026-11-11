package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle

internal fun PlayerPreferences.effectiveLyricsBackground(): String = when {
    style.resolveBackground(background) == PlayerBackgroundStyle.CUSTOM -> "CUSTOM"
    lyricsBackground in setOf("FOLLOW_THEME", "COLORING", "MOVING_BLUR") -> lyricsBackground
    else -> "DEFAULT"
}

@Composable
internal fun PlayerLyricsBackground(c: PlayerRenderContext) {
    val mode = c.preferences.effectiveLyricsBackground()
    val fallback = listOf(Color(0xff202020), Color(0xff141414), Color(0xff050505))
    Box(Modifier.fillMaxSize().testTag("lyrics-background-$mode")
        .background(if (mode == "FOLLOW_THEME") androidx.compose.material3.MaterialTheme.colorScheme.surface else Color.Black)) {
        when (mode) {
            "FOLLOW_THEME" -> Unit
            "MOVING_BLUR" -> BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                val radius = hypot(maxWidth.value, maxHeight.value) / 2f * .85f
                val drift = rememberLyricsBlurDrift(radius, c.motionActive && c.optical.artworkBlur)
                val footprint = maxOf(maxWidth.value, maxHeight.value,
                    2f * (hypot(maxWidth.value, maxHeight.value) / 2f + radius) / 2.4f * 1.02f)
                if (c.optical.artworkBlur) c.slots.artwork(c.state.displayedOccurrenceId,
                    Modifier.requiredSize(footprint.dp).blur(64.dp).graphicsLayer {
                        scaleX = 2.4f; scaleY = 2.4f
                        translationX = drift.xDp.floatValue.dp.toPx()
                        translationY = drift.yDp.floatValue.dp.toPx()
                        rotationZ = drift.rotationDeg.floatValue
                    })
                val colors = fallback.mapIndexed { index, color -> c.state.palette.getOrNull(index) ?: color }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    colors[0].copy(alpha = .55f), colors[1].copy(alpha = .42f), colors[2].copy(alpha = .62f)))))
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .10f)))))
            }
            "COLORING", "CUSTOM" -> {
                val background = when (mode) {
                    "CUSTOM" -> PlayerBackgroundStyle.CUSTOM
                    "COLORING" -> PlayerBackgroundStyle.COLORING
                    else -> PlayerBackgroundStyle.BLUR
                }
                val context = PlayerRenderContext(c.state, c.actions,
                    c.preferences.copy(style = PlayerDesignStyle.V4, background = background),
                    c.interaction, c.slots, c.landscape, c.artworkPager, c.optical)
                PlayerBackground(context)
            }
            else -> {
                if (c.optical.artworkBlur) c.slots.artwork(c.state.displayedOccurrenceId,
                    Modifier.fillMaxSize().blur(46.dp).graphicsLayer { alpha = .62f })
                val colors = fallback.mapIndexed { index, color -> c.state.palette.getOrNull(index) ?: color }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    colors[0].copy(alpha = .88f), colors[1].copy(alpha = .76f), colors[2].copy(alpha = .96f)))))
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)))))
            }
        }
    }
}
