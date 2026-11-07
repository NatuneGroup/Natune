package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot

/**
 * The Classic Home backdrop in both hosts. The desktop page must be one flat colour, the colour of
 * the shell's content surface, because the rail and the top bar beside it are flat
 * `surfaceContainer` surfaces of the same family; the phone page keeps the baseline wash it always
 * had.
 */
@OptIn(ExperimentalTestApi::class)
class HomeBackgroundTest {
    @Composable
    private fun Backdrop(desktop: Boolean, dark: Boolean) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalDesktopLayout provides desktop) {
                Box(Modifier.size(240.dp, 160.dp).testTag("home-page")) {
                    HomeClassicAtmosphereBackground()
                }
            }
        }
    }

    @Test
    fun aDesktopPageIsOneFlatSurface() = runComposeUiTest {
        val scheme = darkColorScheme()
        setContent { Backdrop(desktop = true, dark = true) }
        onNodeWithTag("home-atmosphere-flat").assertExists()
        val page = onNodeWithTag("home-page").captureToImage().toPixelMap()
        val corner = page[0, 0]
        // Every pixel is the page surface: no radial wash, no bottom shade, so the page cannot read
        // as a muddy panel
        // beside the rail.
        for (y in 0 until page.height step 17) {
            for (x in 0 until page.width step 17) {
                assertEquals(corner, page[x, y], "page pixel ($x, $y) is part of a gradient")
            }
        }
        // And it is the scheme's own page surface, one tonal step below the rail and the top bar,
        // not a colour of its
        // own and not the rail's.
        assertEquals(scheme.surface.toArgb(), corner.toArgb())
        assertNotEquals(scheme.surfaceContainer.toArgb(), corner.toArgb())
        assertEquals(1f, corner.alpha)
        onNodeWithTag("home-page").writeM4Screenshot("home-page-desktop-flat.png")
    }

    @Test
    fun thePhonePageKeepsTheBaselineWash() = runComposeUiTest {
        setContent { Backdrop(desktop = false, dark = true) }
        onNodeWithTag("home-atmosphere-flat").assertDoesNotExist()
        val page = onNodeWithTag("home-page").captureToImage().toPixelMap()
        val top = page[page.width / 2, 1]
        val bottom = page[page.width / 2, page.height - 2]
        assertNotEquals(top, bottom, "the phone page lost its vertical wash")
    }
}
