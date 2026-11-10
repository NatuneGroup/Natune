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
            settingsSwitchDisabledReason(key, preferences, active, blur)
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
            settingsSwitchDisabledReason(DirectUsbDriverKey, preferences, true, true))
    }

}
