package moe.rukamori.archivetune.ui.screens.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.*
import org.junit.Assert.*
import org.junit.Test

class SettingsConflictReasonsTest {
    @Test fun searchHasTheSameAudioVideoCacheAndBlurDependenciesAsTheOwningPage() {
        fun reason(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
            preferences: androidx.datastore.preferences.core.Preferences, active: Boolean = false, blur: Boolean = true) =
            settingsSwitchDisabledReason(key, preferences, active, blur, bitPerfectPlatformSupported = true,
                directUsbDriverAvailable = true)
        val bitPerfect = mutablePreferencesOf(BitPerfectUsbOutputKey to true)
        listOf(SkipSilenceKey, AudioNormalizationKey, AudioProcessingEnabledKey,
            RejectSuspectedUpscaledAudioKey).forEach { key ->
            assertEquals(R.string.settings_processing_bypassed_bit_perfect, reason(key, bitPerfect))
            assertEquals(R.string.settings_processing_bypassed_bit_perfect, reason(key, emptyPreferences(), active = true))
        }
        assertEquals(R.string.settings_processing_unavailable_offload,
            reason(SkipSilenceKey, mutablePreferencesOf(AudioOffload to true)))
        assertEquals(R.string.settings_offload_unavailable_authenticity,
            reason(AudioOffload, mutablePreferencesOf(RejectSuspectedUpscaledAudioKey to true)))
        assertEquals(R.string.settings_crossfade_required, reason(CrossfadeGaplessKey, emptyPreferences()))
        assertEquals(R.string.settings_video_required, reason(EnablePipModeKey, emptyPreferences()))
        assertNull(reason(EnablePipModeKey, mutablePreferencesOf(EnableVideoPlaybackKey to true)))
        assertEquals(R.string.settings_caches_required,
            reason(SmartTrimmerKey, mutablePreferencesOf(MaxSongCacheSizeKey to 0, MaxImageCacheSizeKey to 0)))
        assertNull(reason(SmartTrimmerKey, mutablePreferencesOf(MaxSongCacheSizeKey to -1, MaxImageCacheSizeKey to 0)))
        assertEquals(R.string.settings_material_unsupported, reason(NavigationBarFrostedBlurKey, emptyPreferences(), blur = false))
        assertEquals(R.string.settings_liquid_glass_required, reason(LiquidGlassNavBarEnabledKey, emptyPreferences()))
    }

    @Test fun reducedMotionBlocksOnlyTheEffectsTheV2RendererActuallyBypasses() {
        assertEquals(R.string.lyrics_animation_style_v2_only, lyricsAnimationDisabledReason(false, false, false))
        assertEquals(R.string.settings_lyrics_motion_disabled, lyricsAnimationDisabledReason(true, true, true))
        assertNull(lyricsAnimationDisabledReason(true, true, false))
        assertNull(lyricsAnimationDisabledReason(true, false, true))
        assertEquals(R.string.settings_liquid_glass_required,
            miniPlayerMaterialDisabledReason(MiniPlayerBackgroundStyle.LIQUID_GLASS, false, true, false))
        assertNull(miniPlayerMaterialDisabledReason(MiniPlayerBackgroundStyle.THEME, true, false, false))
    }
    @Test fun directUsbSearchRequiresTheStoredParentEvenWhileItsOldSinkIsRunning() {
        val preferences = mutablePreferencesOf()
        assertEquals(R.string.direct_usb_driver_requires_bit_perfect,
            settingsSwitchDisabledReason(DirectUsbDriverKey, preferences, true, true,
                bitPerfectPlatformSupported = true, directUsbDriverAvailable = true))
    }

    @Test fun olderPlatformsCanEnableTheDriverFirstAndThenTheParentInSearch() {
        val preferences = mutablePreferencesOf()
        fun reason(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, active: Boolean = false) =
            settingsSwitchDisabledReason(key, preferences, active, true,
                bitPerfectPlatformSupported = false, directUsbDriverAvailable = true)
        assertNull(reason(DirectUsbDriverKey))
        assertEquals(R.string.bit_perfect_usb_unsupported, reason(BitPerfectUsbOutputKey))
        preferences[DirectUsbDriverKey] = true
        assertNull(reason(BitPerfectUsbOutputKey))
        preferences[BitPerfectUsbOutputKey] = true
        assertEquals(R.string.settings_processing_bypassed_bit_perfect, reason(AudioProcessingEnabledKey))
        preferences[DirectUsbDriverKey] = false
        assertEquals(R.string.bit_perfect_usb_unsupported, reason(BitPerfectUsbOutputKey))
        assertEquals(R.string.settings_processing_bypassed_bit_perfect, reason(AudioProcessingEnabledKey))
        assertEquals(R.string.settings_processing_bypassed_bit_perfect, reason(AudioProcessingEnabledKey, active = true))
        assertEquals(true, preferences[BitPerfectUsbOutputKey])
    }

    @Test fun android14DriverRequiresTheStoredParentAndUnavailableNativeCodeHasItsOwnReason() {
        for (platform in listOf(false, true)) {
            for (parent in listOf(false, true)) {
                val preferences = mutablePreferencesOf(BitPerfectUsbOutputKey to parent, DirectUsbDriverKey to true)
                fun reason(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, native: Boolean) =
                    settingsSwitchDisabledReason(key, preferences, false, true,
                        bitPerfectPlatformSupported = platform, directUsbDriverAvailable = native)
                assertEquals(if (platform && !parent) R.string.direct_usb_driver_requires_bit_perfect else null,
                    reason(DirectUsbDriverKey, true))
                assertEquals(R.string.direct_usb_driver_unavailable, reason(DirectUsbDriverKey, false))
                assertEquals(if (platform) null else R.string.bit_perfect_usb_unsupported,
                    reason(BitPerfectUsbOutputKey, false))
            }
        }
    }

}
