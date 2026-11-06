package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.settings.ThemePaletteStyle

class ArtworkSurfaceThemeTest {
    @Test fun paletteSeedSkipsAFlatWhiteEntry() {
        val seed = artworkPaletteSeed(listOf(Color.White, Color(0xFFFFFFFF), Color(0xFFE54B6C)))
        assertEquals(Color(0xFFE54B6C), seed)
    }

    @Test fun noPaletteKeepsTheHostDefault() {
        assertNull(artworkPaletteSeed(emptyList()))
        assertNull(artworkSurfaceScheme(null, isDark = false, ThemePaletteStyle.AUTOMATIC))
    }

    @Test fun sleeveSeedTintsThePageAndTheRailDifferently() {
        val light = artworkSurfaceScheme(Color(0xFFE54B6C), isDark = false, ThemePaletteStyle.TONAL_SPOT)!!
        assertNotEquals(lightColorScheme().surface, light.surface)
        assertNotEquals(light.surface, light.surfaceContainer)
        val dark = artworkSurfaceScheme(Color(0xFFE54B6C), isDark = true, ThemePaletteStyle.TONAL_SPOT)!!
        assertNotEquals(darkColorScheme().surface, dark.surface)
        assertNotEquals(dark.surface, dark.surfaceContainer)
    }
}
