package moe.rukamori.archivetune.utils

import androidx.compose.runtime.State
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.playback.effectiveAudioOffloadEnabled
import moe.rukamori.archivetune.shared.domain.settings.normalizeSettingsConflicts
import org.junit.Assert.*
import org.junit.Test

class SettingsConflictsPreferencesTest {
    @Test fun directPaletteAndDspActionsAreSingleTransactionsAndKeepUnrelatedNativeValues() = runBlocking {
        val extra = stringSetPreferencesKey("future_native_settings_fixture")
        val store = Store(mutablePreferencesOf(DynamicThemeKey to true, AudioOffload to true,
            LastwaveAudioProcessingKey to true, extra to setOf("preserved")))
        val random = store.snapshot().editAndAwait { setConflictAwareBoolean(RandomThemeOnStartupKey, true) }
        assertEquals(1, store.transactions)
        assertEquals(false, random.after[DynamicThemeKey])
        assertEquals(true, random.after[RandomThemeOnStartupKey])
        val dsp = store.snapshot().editAndAwait { setConflictAwareBoolean(AudioProcessingEnabledKey, true) }
        assertEquals(2, store.transactions)
        assertEquals(false, dsp.after[AudioOffload])
        assertEquals(true, dsp.after[AudioProcessingEnabledKey])
        // The retired engine keys are preset-format input, so this edit must not rewrite them.
        assertEquals(true, dsp.after[LastwaveAudioProcessingKey])
        assertEquals(setOf("preserved"), dsp.after[extra])
    }

    @Test fun fontAndBackgroundNativeBridgesShareCanonicalRules() {
        val preferences = mutablePreferencesOf(UseSystemFontKey to true, CustomFontUriKey to "retained-font")
        preferences.setFontPreference(AppFontPreference.CUSTOM)
        assertEquals("CUSTOM", preferences[FontPreferenceKey])
        assertNull(preferences[UseSystemFontKey])
        assertEquals("retained-font", preferences[CustomFontUriKey])
        preferences.setPlayerBackgroundStyle(PlayerBackgroundStyle.CUSTOM)
        assertEquals("CUSTOM", preferences[LyricsBackgroundStyleKey])
        preferences.setPlayerBackgroundStyle(PlayerBackgroundStyle.GRADIENT)
        assertEquals("DEFAULT", preferences[LyricsBackgroundStyleKey])
        preferences.setCacheCapacity(MaxSongCacheSizeKey, 0)
        preferences.setCacheCapacity(MaxImageCacheSizeKey, 0)
        preferences.setConflictAwareBoolean(SmartTrimmerKey, true)
        assertEquals(false, preferences[SmartTrimmerKey])
    }

    @Test fun nativeMigrationIsIdempotentAndOffloadReadUsesTheWholeSnapshot() {
        val before = mutablePreferencesOf(DynamicThemeKey to true, RandomThemeOnStartupKey to true,
            AudioOffload to true, RejectSuspectedUpscaledAudioKey to true)
        val migrated = before.editSharedPreferences { normalizeSettingsConflicts() }
        assertEquals(false, migrated[RandomThemeOnStartupKey])
        assertEquals(false, migrated[AudioOffload])
        assertEquals(migrated.asMap(), migrated.editSharedPreferences { normalizeSettingsConflicts() }.asMap())
        // A stored offload choice from before the switch existed keeps working: normalization stores it off.
        val ordinary = mutablePreferencesOf(AudioOffload to true).editSharedPreferences { normalizeSettingsConflicts() }
        assertEquals(false, ordinary[AudioProcessingEnabledKey])
        assertTrue(ordinary.effectiveAudioOffloadEnabled(false))
        assertFalse(ordinary.effectiveAudioOffloadEnabled(true))
        listOf(AudioProcessingEnabledKey, CrossfadeEnabledKey, AutomixEnabledKey,
            BitPerfectUsbOutputKey, UsbExclusiveAudioKey).forEach { key ->
            val blocked = ordinary.toMutablePreferences().apply { this[key] = true }
            assertFalse(key.name, blocked.effectiveAudioOffloadEnabled(false))
        }
        assertTrue(migrated.sharedSnapshot().keys.isNotEmpty())
    }

    private class Store(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var transactions = 0
        override val data = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            transactions++
            return transform(values.value).also { values.value = it }
        }
        fun snapshot() = PreferenceSnapshot(object : State<Preferences> {
            override val value get() = values.value
        }, this)
    }
}
