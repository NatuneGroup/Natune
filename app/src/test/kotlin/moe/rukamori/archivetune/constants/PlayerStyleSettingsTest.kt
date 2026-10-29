package moe.rukamori.archivetune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which player styles read each shared player setting, so Settings never offers a row that changes nothing. */
class PlayerStyleSettingsTest {
    private fun honoring(setting: PlayerStyleSetting): Set<String> =
        PlayerDesignStyle.entries.filter { it.honors(setting) }.map { it.name }.toSet()

    @Test
    fun sharedControls_areTheStylesThatDrawThem() {
        val controls = setOf("V1", "V2", "V3", "V4", "V6")
        assertEquals(controls, honoring(PlayerStyleSetting.BUTTONS_STYLE))
        assertEquals(controls, honoring(PlayerStyleSetting.SLIDER_STYLE))
    }

    @Test
    fun sleeveSettings_reachEveryStyleWithAnOrdinaryCover() {
        val shared = setOf("V1", "V2", "V3", "V4", "V6")
        val native = setOf("BITCHORD", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO", "APPLE_MUSIC")
        assertEquals(shared + native + setOf("V8", "V9", "V10"), honoring(PlayerStyleSetting.HIDE_THUMBNAIL))
        assertEquals(shared + native + setOf("V8", "V9"), honoring(PlayerStyleSetting.CORNER_RADIUS))
    }

    @Test
    fun artworkSwipe_coversEveryArtworkDragGesture() {
        assertEquals(
            setOf("V1", "V2", "V3", "V4", "V6", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "BITCHORD", "FLAMINGO"),
            honoring(PlayerStyleSetting.SWIPE_THUMBNAIL),
        )
    }

    @Test
    fun background_skipsStylesThatCoverIt() {
        assertEquals(setOf("V5", "V8", "V9"), PlayerDesignStyle.entries.map { it.name }.toSet() - honoring(PlayerStyleSetting.BACKGROUND))
        assertTrue(PlayerDesignStyle.V5.supportsBackgroundChoice)
    }

    @Test
    fun volumeBar_matchesTheLayoutsWithARow() {
        assertEquals(setOf("V7", "V8", "BITCHORD", "APPLE_MUSIC", "FLAMINGO"), honoring(PlayerStyleSetting.VOLUME_BAR))
    }

    @Test
    fun defaultStyle_honorsEverySharedSettingExceptTheVolumeRow() {
        PlayerStyleSetting.entries.filter { it != PlayerStyleSetting.VOLUME_BAR }.forEach { setting ->
            assertTrue(setting.name, PlayerDesignStyle.Default.honors(setting))
        }
        assertFalse(PlayerDesignStyle.Default.honors(PlayerStyleSetting.VOLUME_BAR))
    }
}
