package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    colorScheme: ColorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
    content: @Composable () -> Unit,
) {
    val appFamily = FontFamily(Font(Res.font.m4_poppins))
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
            motionScheme = if (disableAnimations) DisabledArchiveTuneMotion else MotionScheme.expressive(),
            content = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object DisabledArchiveTuneMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
