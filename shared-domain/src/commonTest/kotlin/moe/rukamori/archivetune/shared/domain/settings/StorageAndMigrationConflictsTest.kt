package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class StorageAndMigrationConflictsTest {
    @Test fun trimmerDependsOnSongOrImageCacheNotDownloadsOrCanvas() {
        for (songs in listOf(-1, 0, 1024)) for (images in listOf(-1, 0, 512)) for (canvas in listOf(-1, 0, 128)) {
            val editor = PreferenceSnapshot.of(MaxSongCacheSizeKey to songs, MaxImageCacheSizeKey to images,
                MaxCanvasCacheSizeKey to canvas).toEditor()
            val available = songs != 0 || images != 0
            assertEquals(available, editor.snapshot().smartTrimmerAvailable())
            editor.setSmartTrimmer(true)
            assertEquals(available, editor[SmartTrimmerKey])
        }
    }

    @Test fun disablingTheLastCacheAndTheTrimmerIsOneEditAndRestoringACacheDoesNotOptIn() {
        val editor = PreferenceSnapshot.of(MaxSongCacheSizeKey to 1024, MaxImageCacheSizeKey to 0, SmartTrimmerKey to true).toEditor()
        editor.setCacheCapacity(MaxSongCacheSizeKey, 0)
        assertEquals(false, editor[SmartTrimmerKey])
        editor.setCacheCapacity(MaxImageCacheSizeKey, -1)
        assertEquals(false, editor[SmartTrimmerKey])
        assertTrue(editor.snapshot().smartTrimmerAvailable())
        assertFailsWith<IllegalArgumentException> { editor.setCacheCapacity(MaxImageCacheSizeKey, -2) }
    }

    @Test fun normalizationResolvesOnlyRealConflictsAndPreservesDormantSettings() {
        val untouched = StringPreferenceKey("future_preference_fixture")
        val editor = PreferenceSnapshot.of(
            DynamicThemeKey to true, RandomThemeOnStartupKey to true,
            NavigationBarFrostedBlurKey to true, NavigationBarTintFrostedBlurKey to true, LiquidGlassNavBarEnabledKey to true,
            RejectSuspectedUpscaledAudioKey to true, AudioOffload to true,
            TryptifyAudioProcessingKey to true, LastwaveAudioProcessingKey to true, FloatDspEnabledKey to true,
            PlayerStreamClientKey to "HI_RES_LOSSLESS", LyricsBackgroundStyleKey to "CUSTOM",
            MaxSongCacheSizeKey to 0, MaxImageCacheSizeKey to 0, SmartTrimmerKey to true,
            AudioNormalizationKey to true, MiniPlayerBackgroundStyleKey to "LIQUID_GLASS",
            DisableBlurKey to true, untouched to "preserved",
        ).toEditor()
        editor.normalizeSettingsConflicts()
        assertEquals(false, editor[RandomThemeOnStartupKey])
        assertEquals(LiquidGlassNavBarEnabledKey, editor.snapshot().selectedNavigationTreatment())
        assertEquals(false, editor[NavigationBarFrostedBlurKey])
        assertEquals(false, editor[NavigationBarTintFrostedBlurKey])
        assertEquals(false, editor[AudioOffload])
        assertEquals(false, editor[LastwaveAudioProcessingKey])
        assertEquals(true, editor[TryptifyAudioProcessingKey])
        assertEquals(true, editor[FloatDspEnabledKey])
        assertEquals("WEB_REMIX", editor[PlayerStreamClientKey])
        assertEquals("DEFAULT", editor[LyricsBackgroundStyleKey])
        assertEquals(false, editor[SmartTrimmerKey])
        assertEquals(true, editor[AudioNormalizationKey])
        assertEquals("LIQUID_GLASS", editor[MiniPlayerBackgroundStyleKey])
        assertEquals("preserved", editor[untouched])
        val before = editor.snapshot()
        editor.normalizeSettingsConflicts()
        assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
    }

    @Test fun anEmptySnapshotDoesNotAcquireNewDefaultsDuringNormalization() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.normalizeSettingsConflicts()
        assertTrue(editor.snapshot().keys.isEmpty())
    }
}
