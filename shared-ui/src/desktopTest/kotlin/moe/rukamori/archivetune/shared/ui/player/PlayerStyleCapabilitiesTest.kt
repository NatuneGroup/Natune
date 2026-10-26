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
        assertEquals(setOf("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V10"), matching { it.sharedControls })
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

    @Test fun blurOffAndLegacyFlamingoVisibilityFollowBaseline() {
        assertEquals(PlayerBackgroundStyle.GRADIENT, effectivePlayerBackground(PlayerBackgroundStyle.BLUR, true))
        assertEquals(PlayerBackgroundStyle.BLUR_GRADIENT, effectivePlayerBackground(PlayerBackgroundStyle.BLUR_GRADIENT, true))
        assertFalse(playerVolumeVisible(PlayerDesignStyle.FLAMINGO, canonical = true, legacyFlamingo = false))
        assertTrue(playerVolumeVisible(PlayerDesignStyle.V8, canonical = true, legacyFlamingo = false))
        assertTrue(playerVolumeVisible(PlayerDesignStyle.FLAMINGO, null, null))
    }
}
