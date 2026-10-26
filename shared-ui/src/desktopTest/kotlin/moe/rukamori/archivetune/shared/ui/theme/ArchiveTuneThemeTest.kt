package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.domain.settings.AppFontPreference
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
class ArchiveTuneThemeTest {
    @Test fun bundledBaselinePoppinsActuallyShapesCommonText() = runComposeUiTest {
        var family: FontFamily? = null
        setContent {
            ArchiveTuneTheme(darkTheme = false) {
                family = LocalArchiveTuneFontFamily.current
                Surface { Text("ArchiveTune Poppins", style = MaterialTheme.typography.headlineLarge) }
            }
        }
        onNodeWithText("ArchiveTune Poppins").assertIsDisplayed()
        runOnIdle { assertNotEquals(FontFamily.Default, family) }
        onRoot().writeM4Screenshot("m4-poppins-theme.png")
    }

    @Test fun typographyPreservesBaselineMetricsAndResolvedFamily() {
        val type = archiveTuneTypography(FontFamily.Serif)
        assertEquals(57.sp, type.displayLarge.fontSize)
        assertEquals(0.sp, type.displayLarge.letterSpacing)
        assertEquals(40.sp, type.headlineLarge.lineHeight)
        assertEquals(.5.sp, type.bodyLarge.letterSpacing)
        assertEquals(FontFamily.Serif, type.labelSmall.fontFamily)
    }

    @Test fun publicExpressiveThemeUsesLiveReducedMotionCustomFontAndDarkOnlyBlack() = runComposeUiTest {
        var reduced by mutableStateOf(false)
        var dark by mutableStateOf(true)
        var scheme: ColorScheme? = null
        var motion: MotionScheme? = null
        var family: FontFamily? = null
        setContent {
            ArchiveTuneTheme(darkTheme = dark, pureBlack = true, disableAnimations = reduced,
                fontPreference = AppFontPreference.CUSTOM, customFontFamily = FontFamily.Serif,
                colorScheme = lightColorScheme(primary = Color.Red)) {
                scheme = MaterialTheme.colorScheme
                motion = MaterialTheme.motionScheme
                family = MaterialTheme.typography.bodyLarge.fontFamily
                Surface { Button({}) { Text("Expressive fixture") } }
            }
        }
        runOnIdle {
            val colors = assertNotNull(scheme)
            assertEquals(Color.Red, colors.primary)
            assertEquals(Color.Black, colors.background)
            assertEquals(FontFamily.Serif, family)
            assertNotSame(DisabledArchiveTuneMotion, motion)
        }
        onRoot().writeM4Screenshot("m4-expressive-theme.png")
        runOnIdle { reduced = true; dark = false }
        runOnIdle {
            assertNotEquals(Color.Black, assertNotNull(scheme).background)
            val currentMotion = assertNotNull(motion)
            val specs = listOf(currentMotion.defaultSpatialSpec<Float>(), currentMotion.fastSpatialSpec<Float>(),
                currentMotion.slowSpatialSpec<Float>(), currentMotion.defaultEffectsSpec<Float>(),
                currentMotion.fastEffectsSpec<Float>(), currentMotion.slowEffectsSpec<Float>())
            specs.forEach {
                val vector = it.vectorize(Float.VectorConverter)
                assertEquals(0L, vector.getDurationNanos(Float.VectorConverter.convertToVector(0f),
                    Float.VectorConverter.convertToVector(1f), Float.VectorConverter.convertToVector(0f)))
            }
        }
    }
}
