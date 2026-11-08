package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.AppFontPreference
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.Font

val LocalArchiveTuneFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Native adapters supply an already-resolved wallpaper/seed palette and custom font.
 * Common UI owns the real Expressive theme, baseline type/shape metrics and live motion policy.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArchiveTuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    disableAnimations: Boolean = false,
    fontPreference: AppFontPreference = AppFontPreference.DEFAULT,
    customFontFamily: FontFamily? = null,
    colorScheme: ColorScheme = archiveTuneDefaultColorScheme(darkTheme),
    content: @Composable () -> Unit,
) {
    // The one bundled family carries real 400/500/600/700 faces. A single Regular file made every Material 3
    // Medium/SemiBold/Bold style either flat or a synthesised weight, so the type hierarchy had one voice.
    // Scripts outside Latin/Devanagari come from the platform's own font stack (see SystemFontFallback).
    val appFamily = FontFamily(
        Font(Res.font.m4_poppins, FontWeight.Normal),
        Font(Res.font.m4_poppins_medium, FontWeight.Medium),
        Font(Res.font.m4_poppins_semibold, FontWeight.SemiBold),
        Font(Res.font.m4_poppins_bold, FontWeight.Bold),
    )
    val family = when (fontPreference) {
        AppFontPreference.DEFAULT -> appFamily
        AppFontPreference.SYSTEM -> FontFamily.Default
        AppFontPreference.CUSTOM -> customFontFamily ?: appFamily
    }
    val type = remember(family) { archiveTuneTypography(family) }
    val colors = remember(colorScheme, darkTheme, pureBlack) {
        if (darkTheme && pureBlack) colorScheme.copy(surface = Color.Black, background = Color.Black) else colorScheme
    }
    val shapes = remember { Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp),
        RoundedCornerShape(16.dp), RoundedCornerShape(24.dp), RoundedCornerShape(32.dp)) }
    CompositionLocalProvider(LocalArchiveTuneFontFamily provides family) {
        MaterialExpressiveTheme(colorScheme = colors, typography = type, shapes = shapes,
            motionScheme = if (disableAnimations) DisabledArchiveTuneMotion else MotionScheme.expressive()) {
            // Text drawn outside a Surface (custom-painted cards, scaffold-free roots) otherwise
            // falls back to Color.Black and vanishes on dark schemes.
            CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
        }
    }
}

/**
 * The scheme used when no palette is saved. The default parameter and the root both read it, so the default look
 * has one definition.
 */
internal fun archiveTuneDefaultColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) darkColorScheme() else lightColorScheme()

/**
 * The window's own background, for the hosts that own the pixels Compose does not paint: the strip an in-flight
 * resize has not repainted yet and whatever the compositor cuts out of a rounded window corner. The toolkit's
 * default there is black, which reads as a hole in the window; the app's background colour reads as the page.
 *
 * It follows the default scheme rather than a saved wallpaper palette because it is only ever visible for a frame or
 * in a corner, and the dark and light backgrounds are what the window is surrounded by either way.
 */
fun archiveTuneWindowBackground(darkTheme: Boolean): Color = archiveTuneDefaultColorScheme(darkTheme).background

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object DisabledArchiveTuneMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
