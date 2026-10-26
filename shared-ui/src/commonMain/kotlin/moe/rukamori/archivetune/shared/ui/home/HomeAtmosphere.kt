package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

private val HomeWelcomeGutter = 20.dp

/**
 * The home atmosphere wash. Dark surfaces draw nothing unless [subtle] is set, so the root's own gradient shows through,
 * as the baseline home screen does.
 */
@Composable
fun HomeAtmosphereBackground(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    if (dark && !subtle) {
        return
    }
    val base = MaterialTheme.colorScheme.surface

    val glow = if (subtle && dark) 0.20f else 0.12f
    val topWashAlpha = when {
        subtle && dark -> 0.13f
        subtle -> 0.16f
        else -> 0.5f
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = topWashAlpha), Color.Transparent),
                            startY = 0f,
                            endY = if (dark) h * 0.22f else h * 0.16f,
                        )
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.10f, h * 0.12f),
                            radius = if (subtle && dark) w * 0.90f else w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.32f),
                            radius = if (subtle && dark) w * 0.80f else w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.15f, h * 0.90f),
                            radius = if (subtle && dark) w * 0.90f else w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(
                                    alpha = when {
                                        subtle && dark -> 0.28f
                                        dark -> 0.30f
                                        else -> 0.05f
                                    },
                                ),
                            ),
                            startY = h * 0.50f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

/**
 * The Classic Home backdrop, ported from the baseline Home feed design (Portions © vossgraves). Unlike
 * [HomeAtmosphereBackground] it always draws, light and dark alike, so the Classic feed and the Spotify surface keep
 * the baseline glow. It has its own name because both overloads default every parameter; sharing one name would leave a
 * bare call to overload specificity.
 */
@Composable
fun HomeClassicAtmosphereBackground(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    // The base comes from the theme rather than a colour of its own, so the top-fade scrim keyed to the page colour keeps
    // matching, and a pure-black page has nothing to mismatch with.
    val base =
        if (dark && surface != Color.Black) {
            MaterialTheme.colorScheme.surfaceContainerLowest
        } else {
            surface
        }
    val glow = if (dark) 0.17f else 0.12f
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        if (dark) {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.22f,
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.16f,
                            )
                        }
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.12f, h * 0.10f),
                            radius = w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.30f),
                            radius = w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.92f),
                            radius = w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.30f else 0.05f)),
                            startY = h * 0.55f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

/** Greeting for the signed-in account name; a blank name falls back to the anonymous greeting. */
@Composable
fun HomeWelcomeHeader(
    accountName: String,
    modifier: Modifier = Modifier,
) {
    val small = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val headline = MaterialTheme.colorScheme.onSurface
    val displayName = accountName.trim()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = HomeWelcomeGutter)
                .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Text(
            text =
                if (displayName.isNotBlank()) {
                    stringResource(Res.string.m4_home_welcome_line, displayName)
                } else {
                    stringResource(Res.string.m4_home_welcome_line_anonymous)
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = small,
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(Res.string.m4_home_headline_line1),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
        Text(
            text = stringResource(Res.string.m4_home_headline_line2),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
    }
}
