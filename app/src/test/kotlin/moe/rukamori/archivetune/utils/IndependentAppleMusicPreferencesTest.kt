package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.shared.domain.settings.interfaceStyle
import moe.rukamori.archivetune.shared.domain.settings.migrateAppleMusicSettings
import moe.rukamori.archivetune.ui.component.applyInterfaceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentAppleMusicPreferencesTest {
    @Test fun migrationPreservesUnknownNativeSettingsAndRunsOnlyOnce() {
        val unknown = stringSetPreferencesKey("future_native_setting")
        val legacy = mutablePreferencesOf(
            AppleMusicExperienceKey to true,
            NavigationBarStyleKey to "FLOATING",
            unknown to setOf("one", "two"),
        )
        val migrated = legacy.editSharedPreferences { migrateAppleMusicSettings() }
        assertEquals("APPLE_MUSIC", migrated[NavigationBarStyleKey])
        assertEquals("FLOATING", migrated[NavigationStyleBeforeAppleMusicKey])
        assertEquals(setOf("one", "two"), migrated[unknown])
        val manual = migrated.toMutablePreferences().apply { this[AppleMusicHeadersKey] = false }
        val repeated = manual.editSharedPreferences { migrateAppleMusicSettings() }
        assertEquals(manual.asMap(), repeated.asMap())
        assertEquals("CUSTOM", repeated.sharedSnapshot().interfaceStyle().name)
    }

    @Test fun changingTheTabBarAfterAPresetIsNotOverriddenByItsLegacyFlag() {
        val preferences = mutablePreferencesOf()
        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        preferences[NavigationBarStyleKey] = "DEFAULT"
        assertEquals(true, preferences[AppleMusicExperienceKey])
        assertEquals("CUSTOM", preferences.sharedSnapshot().interfaceStyle().name)
        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        assertEquals("APPLE_MUSIC", preferences[NavigationBarStyleKey])
        assertTrue(preferences[AppleMusicHeadersKey] == true)
    }
}
