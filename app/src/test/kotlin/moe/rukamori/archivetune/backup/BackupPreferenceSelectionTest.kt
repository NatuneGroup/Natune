package moe.rukamori.archivetune.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceSelectionTest {
    @Test
    fun anAccountOnlySelectionStillCarriesThePreferencesPayload() {
        assertTrue(BackupPreferenceSelection.carriesPreferences(settings = false, account = true))
        assertTrue(BackupPreferenceSelection.carriesPreferences(settings = true, account = false))
        assertFalse(BackupPreferenceSelection.carriesPreferences(settings = false, account = false))
    }

    @Test
    fun anAccountOnlySelectionKeepsSignInsAndDropsSettings() {
        listOf("innerTubeCookie", "spotify_sp_dc", "tidalRefreshToken", "deezerArl", "qqMusicUin").forEach { key ->
            assertTrue(key, BackupPreferenceSelection.includes(key, settings = false, account = true))
        }
        assertFalse(BackupPreferenceSelection.includes("darkMode", settings = false, account = true))
    }

    @Test
    fun aSettingsOnlySelectionLeavesEverySignInBehind() {
        listOf("innerTubeOAuthRefreshToken", "appleMusicMediaUserToken", "listenTogetherSessionToken").forEach { key ->
            assertFalse(key, BackupPreferenceSelection.includes(key, settings = true, account = false))
        }
        assertTrue(BackupPreferenceSelection.includes("darkMode", settings = true, account = false))
    }

    @Test
    fun accountSlotPrefixesFollowTheAccountCategory() {
        assertTrue(BackupPreferenceSelection.includes("account.youtube.id", settings = false, account = true))
        assertFalse(BackupPreferenceSelection.includes("spotify_stream_cookie", settings = true, account = false))
    }

    @Test
    fun deviceBoundValuesNeverTravel() {
        listOf("poolTidalAccounts", "poolAccountsLastRefreshAtMillis", "secure_store_youtube").forEach { key ->
            assertFalse(key, BackupPreferenceSelection.includes(key, settings = true, account = true))
        }
    }

    @Test
    fun onlyAFullPreferencesSelectionMayReplaceTheLiveFile() {
        assertTrue(BackupPreferenceSelection.replacesWholeFile(settings = true, account = true))
        assertFalse(BackupPreferenceSelection.replacesWholeFile(settings = false, account = true))
        assertFalse(BackupPreferenceSelection.replacesWholeFile(settings = true, account = false))
    }

    @Test
    fun readyMarkerConsistencyAcceptsAccountOnlyMerges() {
        assertTrue(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.XML))
        // An account-only backup made before any sign-in stages no preferences at all.
        assertTrue(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.NONE))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = true, account = false, kind = SettingsKind.NONE))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.PB))
        assertTrue(BackupPreferenceSelection.isConsistent(settings = true, account = true, kind = SettingsKind.PB))
        assertEquals(false, BackupPreferenceSelection.isConsistent(settings = false, account = false, kind = SettingsKind.XML))
    }
}
