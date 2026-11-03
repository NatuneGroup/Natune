package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the persisted preference surface. Each on-disk name is a contract with data users already store, so a key that
 * is added, removed or renamed has to change this test on purpose.
 */
class PersistedPreferenceRegistrySettingsTest {
    @Test
    fun registryHoldsEveryGeneratedKeyExactlyOnce() {
        assertEquals(664, PersistedPreferenceKeys.size)
        assertEquals(PersistedPreferenceKeys.size, PersistedPreferenceKeys.toSet().size)
    }

    @Test
    fun aNameIsUniquePerValueKind() {
        val identities = PersistedPreferenceKeys.map { it.kind to it.name }

        assertEquals(identities.size, identities.toSet().size)
    }

    @Test
    fun onlyTheLegacyFloatToIntMigrationPairSharesAName() {
        val repeated = PersistedPreferenceKeys.groupBy { it.name }.filterValues { it.size > 1 }

        assertEquals(setOf("historyDuration"), repeated.keys)
        assertEquals(
            setOf(PreferenceKind.FLOAT, PreferenceKind.INT),
            repeated.getValue("historyDuration").map { it.kind }.toSet(),
        )
    }

    @Test
    fun policyKeysKeepTheirPersistedNamesAndKinds() {
        assertEquals("playerDesignStyle", PlayerDesignStyleKey.name)
        assertEquals("libraryStyle", LibraryStyleKey.name)
        assertEquals("apple_music_experience", AppleMusicExperienceKey.name)
        assertEquals("style_before_apple_music", StyleBeforeAppleMusicKey.name)
        assertEquals(PreferenceKind.BOOLEAN, AppleMusicExperienceKey.kind)
        assertEquals(PreferenceKind.STRING, StyleBeforeAppleMusicKey.kind)
        assertEquals(PreferenceKind.FLOAT, UiScaleFactorKey.kind)
        assertEquals("uiScaleFactor", UiScaleFactorKey.name)
        assertEquals("fontPreference", FontPreferenceKey.name)
        assertEquals("useSystemFont", UseSystemFontKey.name)
        assertEquals("customFontUri", CustomFontUriKey.name)
        assertEquals("customFontName", CustomFontNameKey.name)
        assertEquals("tabletModeEnabled", TabletModeEnabledKey.name)
        assertEquals("liquidGlassEnabled", LiquidGlassEnabledKey.name)
        assertEquals("liquidGlassNavBarEnabled", LiquidGlassNavBarEnabledKey.name)
        assertEquals("navigationBarFrostedBlur", NavigationBarFrostedBlurKey.name)
        assertEquals("navigationBarTintFrostedBlur", NavigationBarTintFrostedBlurKey.name)
        assertEquals("bitPerfectUsbOutput", BitPerfectUsbOutputKey.name)
        assertEquals("crossfadeEnabled", CrossfadeEnabledKey.name)
        assertEquals("automixEnabled", AutomixEnabledKey.name)
        assertEquals("usbExclusiveAudio", UsbExclusiveAudioKey.name)
        assertEquals("youtubeMusicRegion", YouTubeMusicRegionKey.name)
        assertEquals("library_source", LibrarySourceKey.name)
        assertEquals("libraryChipOrder", LibraryChipOrderKey.name)
        assertEquals("chipSortType", ChipSortTypeKey.name)
        assertEquals("nativeIosInterface", NativeIosInterfaceKey.name)
        assertEquals(PreferenceKind.BOOLEAN, NativeIosInterfaceKey.kind)
    }

    @Test
    fun everyPolicyKeyIsRegistered() {
        val policyKeys =
            listOf(
                PlayerDesignStyleKey,
                LibraryStyleKey,
                AppleMusicExperienceKey,
                AppleMusicMenusKey,
                AppleMusicSearchBarKey,
                AppleMusicPaletteKey,
                AppleMusicSettingsMigratedKey,
                NavigationStyleBeforeAppleMusicKey,
                StyleBeforeAppleMusicKey,
                SponsorBlockPodcastsEnabledKey,
                UiScaleFactorKey,
                FontPreferenceKey,
                UseSystemFontKey,
                CustomFontUriKey,
                CustomFontNameKey,
                TabletModeEnabledKey,
                LiquidGlassEnabledKey,
                LiquidGlassNavBarEnabledKey,
                LiquidGlassIntensityKey,
                LiquidGlassRefractionHeightKey,
                LiquidGlassRefractionAmountKey,
                LiquidGlassBlurRadiusKey,
                LiquidGlassTintOpacityKey,
                LiquidGlassShadowDepthKey,
                LiquidGlassDepth3DKey,
                LiquidGlassChromaticAberrationKey,
                LiquidGlassBackdropVibrancyKey,
                LiquidGlassAdaptiveLuminanceKey,
                NavigationBarFrostedBlurKey,
                NavigationBarTintFrostedBlurKey,
                BitPerfectUsbOutputKey,
                CrossfadeEnabledKey,
                AutomixEnabledKey,
                UsbExclusiveAudioKey,
                YouTubeMusicRegionKey,
                SongSortTypeKey,
                SongSortDescendingKey,
                SongFilterKey,
                ArtistFilterKey,
                ArtistSortTypeKey,
                ArtistSortDescendingKey,
                AlbumFilterKey,
                AlbumSortTypeKey,
                AlbumSortDescendingKey,
                PlaylistSortTypeKey,
                PlaylistSortDescendingKey,
                AutoPlaylistSongSortTypeKey,
                AutoPlaylistSongSortDescendingKey,
                ArtistSongSortTypeKey,
                ArtistSongSortDescendingKey,
                ChipSortTypeKey,
                LibraryChipOrderKey,
                LibrarySourceKey,
                NativeIosInterfaceKey,
            )

        for (key in policyKeys) {
            assertTrue(PersistedPreferenceKeys.contains(key), "missing registry entry for ${key.name}")
        }
    }
}
