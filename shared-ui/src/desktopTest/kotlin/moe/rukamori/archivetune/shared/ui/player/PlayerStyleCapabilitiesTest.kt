package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*

/** Characterization of PreferenceKeys/PlayerVolumePreferences in the pinned Android baseline. */
class PlayerStyleCapabilitiesTest {
    @Test fun savedPrefixAndDefaultStayUnchanged() {
        assertEquals(
            listOf("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "APPLE_MUSIC", "V10",
                "BITCHORD", "TIKTOK", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO"),
            PlayerDesignStyle.entries.take(17).map { it.name },
        )
        assertEquals(PlayerDesignStyle.V4, PlayerDesignStyle.Default)
    }

    @Test fun legacyCapabilitiesAreExactNotFamilyGuesses() {
        fun matching(test: (PlayerCapabilities) -> Boolean) =
            PlayerDesignStyle.entries.take(17).filter { test(it.capabilities) }.map { it.name }.toSet()
        // V5 has no transport, and V7 and V10 draw their own; only these five draw the shared controls.
        assertEquals(setOf("V1", "V2", "V3", "V4", "V6"), matching { it.sharedControls })
        assertEquals(setOf("V7", "V8", "BITCHORD", "APPLE_MUSIC", "FLAMINGO"), matching { it.volume })
        assertEquals(setOf("SIMPMUSIC", "SPATIALFLOW", "LOOPER"), matching { it.horizontalSwipe })
        assertEquals(setOf("APPLE_MUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO"), matching { it.artworkCanvas })
        assertEquals(setOf("V8", "V9"), matching { !it.backgroundChoice })
    }

    @Test fun explicitChoiceSurvivesNativeStyleSwitchWithoutWritingStorage() {
        for (style in PlayerDesignStyle.entries.take(17)) {
            for (stored in PlayerBackgroundStyle.entries) {
                val expected = if (style in listOf(PlayerDesignStyle.V8, PlayerDesignStyle.V9)) {
                    PlayerBackgroundStyle.DEFAULT
                } else if (stored == PlayerBackgroundStyle.DEFAULT) style.capabilities.nativeBackground else stored
                assertEquals(expected, style.resolveBackground(stored))
            }
        }
    }

    @Test fun settingAnswersMatchTheAndroidHostPerStyle() {
        fun honoring(setting: PlayerStyleSetting) = PlayerDesignStyle.entries.filter { it.honors(setting) }.map { it.name }.toSet()
        val shared = setOf("V1", "V2", "V3", "V4", "V6")
        val sleeves = setOf("BITCHORD", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO", "APPLE_MUSIC")
        assertEquals(shared + sleeves + setOf("V8", "V9", "V10", "METROLIST_KMP"), honoring(PlayerStyleSetting.HIDE_THUMBNAIL))
        assertEquals(shared + sleeves + setOf("V8", "V9"), honoring(PlayerStyleSetting.CORNER_RADIUS))
        assertEquals(setOf("V5", "V8", "V9"), PlayerDesignStyle.entries.map { it.name }.toSet() - honoring(PlayerStyleSetting.BACKGROUND))
        assertEquals(shared, honoring(PlayerStyleSetting.BUTTONS_STYLE))
        assertEquals(shared, honoring(PlayerStyleSetting.SLIDER_STYLE))
    }

    @Test fun sleevePreferencesApplyOnlyWhereTheStyleReadsThem() {
        fun prefs(style: PlayerDesignStyle) = PreferenceSnapshot.of(PlayerDesignStyleKey to style.name,
            HidePlayerThumbnailKey to true, ThumbnailCornerRadiusKey to 40f).playerPreferences()
        assertTrue(prefs(PlayerDesignStyle.V4).sleeveHidden)
        assertTrue(prefs(PlayerDesignStyle.V8).sleeveHidden)
        assertFalse(prefs(PlayerDesignStyle.TIKTOK).sleeveHidden)
        assertFalse(prefs(PlayerDesignStyle.V7).sleeveHidden)
        assertEquals(40f, prefs(PlayerDesignStyle.V9).sleeveRadiusOr(30f))
        assertEquals(12f, prefs(PlayerDesignStyle.METROLIST_KMP).sleeveRadiusOr(12f))
    }

    @Test fun blurOffAndLegacyFlamingoVisibilityFollowBaseline() {
        assertEquals(PlayerBackgroundStyle.GRADIENT, effectivePlayerBackground(PlayerBackgroundStyle.BLUR, true))
        assertEquals(PlayerBackgroundStyle.BLUR_GRADIENT, effectivePlayerBackground(PlayerBackgroundStyle.BLUR_GRADIENT, true))
        assertFalse(playerVolumeVisible(PlayerDesignStyle.FLAMINGO, canonical = true, legacyFlamingo = false))
        assertTrue(playerVolumeVisible(PlayerDesignStyle.V8, canonical = true, legacyFlamingo = false))
        assertTrue(playerVolumeVisible(PlayerDesignStyle.FLAMINGO, null, null))
    }
}
