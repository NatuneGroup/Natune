package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.settings.DesktopArtworkSeedKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
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

    /**
     * The first frame of a desktop window has no sleeve yet. It must already be the app's own family, not Material's
     * stock purple, and the sleeve the window remembered from its last session must win over the brand seed.
     */
    @Test fun theDesktopFirstFrameIsNeverStockPurple() {
        val first = desktopFirstFrameScheme(null, isDark = false, ThemePaletteStyle.TONAL_SPOT)
        assertNotEquals(lightColorScheme().surface, first.surface)
        assertNotEquals(lightColorScheme().surfaceContainer, first.surfaceContainer)
        assertEquals(artworkSurfaceScheme(DesktopDefaultThemeSeed, false, ThemePaletteStyle.TONAL_SPOT)!!.surface, first.surface)

        val darkFirst = desktopFirstFrameScheme(null, isDark = true, ThemePaletteStyle.TONAL_SPOT)
        assertNotEquals(darkColorScheme().surface, darkFirst.surface)

        val remembered = Color(0xFFE54B6C)
        assertEquals(
            artworkSurfaceScheme(remembered, false, ThemePaletteStyle.TONAL_SPOT)!!.surface,
            desktopFirstFrameScheme(remembered, isDark = false, ThemePaletteStyle.TONAL_SPOT).surface,
        )
    }

    /** A sleeve remembered for the next launch reads back as the colour it was stored as. */
    @Test fun theRememberedSleeveSeedSurvivesTheRoundTrip() {
        val sleeve = Color(0xFFE54B6C)
        val snapshot = PreferenceSnapshot.of(DesktopArtworkSeedKey to sleeve.toArgb().toLong())
        assertEquals(sleeve.toArgb(), snapshot.desktopArtworkSeed()!!.toArgb())
        assertNull(PreferenceSnapshot.EMPTY.desktopArtworkSeed())
        assertNull(PreferenceSnapshot.of(DesktopArtworkSeedKey to 0L).desktopArtworkSeed())
    }
}
