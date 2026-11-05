package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import moe.rukamori.archivetune.constants.AppFontPreference
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AutomixEnabledKey
import moe.rukamori.archivetune.constants.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CustomFontUriKey
import moe.rukamori.archivetune.constants.FontPreferenceKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.MaxImageCacheSizeKey
import moe.rukamori.archivetune.constants.MaxSongCacheSizeKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.constants.UiScaleFactorKey
import moe.rukamori.archivetune.constants.UseSystemFontKey
import moe.rukamori.archivetune.constants.UsbExclusiveAudioKey
import moe.rukamori.archivetune.constants.YouTubeMusicRegionKey
import moe.rukamori.archivetune.resolveImageDiskCacheConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror is what a process-lifetime reader sees when DataStore's first snapshot has not landed:
 * it must reproduce the stored values exactly, leave absent keys absent (the reader's own cached
 * default), and drop corrupt entries instead of surfacing the wrong type.
 *
 * Threading is not exercised here: plain JVM tests have no Android Looper or SharedPreferences. The
 * codec and the pure decision are covered; the Looper/SharedPreferences wiring is source-reviewed.
 */
class StartupPreferenceMirrorTest {
    private fun everyStartupKey() = mutablePreferencesOf(
        MaxImageCacheSizeKey to 128,
        MaxSongCacheSizeKey to -1,
        SmartTrimmerKey to true,
        BitPerfectUsbOutputKey to true,
        CrossfadeEnabledKey to false,
        AutomixEnabledKey to true,
        UsbExclusiveAudioKey to true,
        AudioOffload to true,
        UiScaleFactorKey to 0.85f,
        FontPreferenceKey to AppFontPreference.CUSTOM.name,
        UseSystemFontKey to true,
        CustomFontUriKey to "content://fonts/a b?x=1:2\nünicode",
        YouTubeMusicRegionKey to "JP",
        TabletModeEnabledKey to true,
        LiquidGlassEnabledKey to true,
        LiquidGlassNavBarEnabledKey to false,
        NavigationBarFrostedBlurKey to true,
        NavigationBarTintFrostedBlurKey to false,
    )

    private fun Preferences.mirrored(): Preferences = decodeStartupPreferences(encodeStartupPreferences(this))

    @Test
    fun `every stored startup value survives the mirror`() {
        val stored = everyStartupKey()

        assertEquals(stored.asMap(), stored.mirrored().asMap())
    }

    @Test
    fun `absent keys stay absent so the caller keeps its cached default`() {
        val stored = mutablePreferencesOf(SmartTrimmerKey to true)

        val mirrored = stored.mirrored()

        assertEquals(true, mirrored[SmartTrimmerKey])
        assertNull(mirrored[MaxImageCacheSizeKey])
        assertEquals(
            resolveImageDiskCacheConfig(stored[MaxImageCacheSizeKey]).maxSizeBytes,
            resolveImageDiskCacheConfig(mirrored[MaxImageCacheSizeKey]).maxSizeBytes,
        )
    }

    @Test
    fun `a corrupt entry is dropped instead of reaching a reader`() {
        val corrupt = mapOf(
            "i:${MaxImageCacheSizeKey.name}" to "not-a-number",
            // Floats travel as raw bits, so a decimal value is not accepted.
            "f:${UiScaleFactorKey.name}" to "0.85",
            // Only the exact lowercase spelling is a Boolean.
            "b:${SmartTrimmerKey.name}" to "TRUE",
            "i:${SmartTrimmerKey.name}" to "1",
            "s:${MaxImageCacheSizeKey.name}" to "128",
            "s:${FontPreferenceKey.name}" to AppFontPreference.SYSTEM.name,
        )

        val mirrored = decodeStartupPreferences(corrupt)

        assertNull(mirrored[MaxImageCacheSizeKey])
        assertNull(mirrored[UiScaleFactorKey])
        assertNull(mirrored[SmartTrimmerKey])
        assertEquals(AppFontPreference.SYSTEM.name, mirrored[FontPreferenceKey])
    }

    @Test
    fun `keys outside the startup set never enter the mirror`() {
        val stored = mutablePreferencesOf(
            SmartTrimmerKey to true,
            stringPreferencesKey("innerTubeCookie") to "secret",
            intPreferencesKey("someOtherCounter") to 7,
        )

        val encoded = encodeStartupPreferences(stored)

        assertEquals(setOf("b:${SmartTrimmerKey.name}"), encoded.keys)
        assertNull(decodeStartupPreferences(mapOf("s:innerTubeCookie" to "secret"))[stringPreferencesKey("innerTubeCookie")])
    }

    @Test
    fun `restart scoped settings read their first values from the mirror`() {
        val stored = mutablePreferencesOf(
            UiScaleFactorKey to 1.2f,
            FontPreferenceKey to AppFontPreference.CUSTOM.name,
            UseSystemFontKey to false,
            CustomFontUriKey to "content://font/x",
            TabletModeEnabledKey to true,
            LiquidGlassEnabledKey to true,
            LiquidGlassNavBarEnabledKey to true,
            NavigationBarFrostedBlurKey to false,
            NavigationBarTintFrostedBlurKey to false,
        )

        assertEquals(RestartScopedSettings.from(stored), RestartScopedSettings.from(stored.mirrored()))
    }

    @Test
    fun `cache bounds and sink switches keep their stored values`() {
        for (imageCacheSize in listOf(-1, 0, 64, 512)) {
            val stored = mutablePreferencesOf(MaxImageCacheSizeKey to imageCacheSize, MaxSongCacheSizeKey to 256)
            val mirrored = stored.mirrored()

            assertEquals(imageCacheSize, mirrored[MaxImageCacheSizeKey])
            assertEquals(
                resolveImageDiskCacheConfig(stored[MaxImageCacheSizeKey]),
                resolveImageDiskCacheConfig(mirrored[MaxImageCacheSizeKey]),
            )
            assertEquals(256, mirrored[MaxSongCacheSizeKey])
        }

        val stored = mutablePreferencesOf(
            BitPerfectUsbOutputKey to true,
            CrossfadeEnabledKey to true,
            AutomixEnabledKey to false,
            UsbExclusiveAudioKey to true,
        )
        val mirrored = stored.mirrored()

        assertEquals(true, mirrored[BitPerfectUsbOutputKey])
        assertEquals(true, mirrored[CrossfadeEnabledKey])
        assertEquals(false, mirrored[AutomixEnabledKey])
        assertEquals(true, mirrored[UsbExclusiveAudioKey])
    }

    @Test
    fun `every synchronous startup read has a mirrored key`() {
        val required: List<Preferences.Key<*>> = listOf(
            MaxImageCacheSizeKey,
            SmartTrimmerKey,
            MaxSongCacheSizeKey,
            BitPerfectUsbOutputKey,
            CrossfadeEnabledKey,
            AutomixEnabledKey,
            UsbExclusiveAudioKey,
            AudioOffload,
            UiScaleFactorKey,
            FontPreferenceKey,
            UseSystemFontKey,
            CustomFontUriKey,
            TabletModeEnabledKey,
            LiquidGlassEnabledKey,
            LiquidGlassNavBarEnabledKey,
            NavigationBarFrostedBlurKey,
            NavigationBarTintFrostedBlurKey,
            YouTubeMusicRegionKey,
        )

        required.forEach { key -> assertTrue(key.name, key.name in startupPreferenceTags) }
    }

    @Test
    fun `loaded state always wins`() {
        assertEquals(
            StartupLoadAction.USE_LOADED,
            startupLoadAction(loaded = true, started = true, mainThread = true, mirrorAvailable = false),
        )
        assertEquals(
            StartupLoadAction.USE_LOADED,
            startupLoadAction(loaded = true, started = false, mainThread = false, mirrorAvailable = true),
        )
    }

    @Test
    fun `a store that never started cannot wait for a snapshot that is not coming`() {
        assertEquals(
            StartupLoadAction.USE_LOADED,
            startupLoadAction(loaded = false, started = false, mainThread = true, mirrorAvailable = false),
        )
    }

    @Test
    fun `the main thread uses the mirror instead of waiting`() {
        assertEquals(
            StartupLoadAction.USE_MIRROR,
            startupLoadAction(loaded = false, started = true, mainThread = true, mirrorAvailable = true),
        )
    }

    @Test
    fun `a missing mirror keeps the one time bounded wait`() {
        assertEquals(
            StartupLoadAction.WAIT,
            startupLoadAction(loaded = false, started = true, mainThread = true, mirrorAvailable = false),
        )
    }

    @Test
    fun `background callers wait for real values instead of the mirror`() {
        assertEquals(
            StartupLoadAction.WAIT,
            startupLoadAction(loaded = false, started = true, mainThread = false, mirrorAvailable = false),
        )
    }
}
