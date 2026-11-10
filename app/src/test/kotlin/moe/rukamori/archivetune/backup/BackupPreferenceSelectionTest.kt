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
        // The Spotify Connect device id is bound to another install's registration, so it stays out of
        // both categories, exactly as the shared backup policy already excludes it.
        listOf(
            "poolTidalAccounts",
            "poolAccountsLastRefreshAtMillis",
            "secure_store_youtube",
            "spotifyStreamDeviceId",
        ).forEach { key ->
            assertFalse(key, BackupPreferenceSelection.includes(key, settings = true, account = true))
            assertFalse(key, BackupPreferenceSelection.includes(key, settings = false, account = true))
            assertFalse(key, BackupPreferenceSelection.includes(key, settings = true, account = false))
        }
    }

    @Test
    fun protobufPayloadsAlwaysMergeThroughXmlInsteadOfReplacingTheFile() {
        // Even a full selection excludes device-bound and secure-store values, so a raw install is never safe.
        assertTrue(BackupPreferenceSelection.needsConversion(SettingsKind.PB))
        assertFalse(BackupPreferenceSelection.needsConversion(SettingsKind.XML))
        assertFalse(BackupPreferenceSelection.needsConversion(SettingsKind.NONE))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = true, account = true, kind = SettingsKind.PB))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.PB))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = true, account = false, kind = SettingsKind.PB))
    }

    @Test
    fun anAccountOnlyBackupWithoutSignInsStagesASuccessfulNoOp() {
        assertTrue(BackupPreferenceSelection.isAccountOnlyNoOp(settings = false, account = true, kind = SettingsKind.NONE))
        // A settings selection with no payload is still a missing backup, not a no-op.
        assertFalse(BackupPreferenceSelection.isAccountOnlyNoOp(settings = true, account = true, kind = SettingsKind.NONE))
        assertFalse(BackupPreferenceSelection.isAccountOnlyNoOp(settings = false, account = true, kind = SettingsKind.XML))
        assertFalse(BackupPreferenceSelection.isAccountOnlyNoOp(settings = false, account = false, kind = SettingsKind.NONE))
    }

    @Test
    fun readyMarkerConsistencyAcceptsAccountOnlyMerges() {
        assertTrue(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.XML))
        // An account-only backup made before any sign-in stages no preferences at all.
        assertTrue(BackupPreferenceSelection.isConsistent(settings = false, account = true, kind = SettingsKind.NONE))
        assertFalse(BackupPreferenceSelection.isConsistent(settings = true, account = false, kind = SettingsKind.NONE))
        assertEquals(false, BackupPreferenceSelection.isConsistent(settings = false, account = false, kind = SettingsKind.XML))
    }
}
