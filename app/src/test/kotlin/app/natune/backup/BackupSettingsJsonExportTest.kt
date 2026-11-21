package app.natune.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.natune.shared.data.backup.BackupSettingsReader
import app.natune.shared.domain.settings.BooleanPreferenceKey
import app.natune.shared.domain.settings.StringPreferenceKey
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSettingsJsonExportTest {
    @Test
    fun settingsJsonCarriesSettingsButNotCredentialsOrRefetchableCaches() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("darkMode") to "ON",
            booleanPreferencesKey("dynamicTheme") to false,
            stringPreferencesKey("innerTubeCookie") to "SAPISID=secret",
            stringPreferencesKey("github_releases_json") to "[]",
            stringPreferencesKey("spotify_library_playlists_cache") to "{}",
        )
        val output = ByteArrayOutputStream()

        writeBackupSettingsToJson(output, preferences, setOf(BackupArchiveCategory.SETTINGS))
        val read = BackupSettingsReader.read(output.toByteArray(), foreign = false)

        assertTrue(read.skipped.isEmpty())
        assertEquals("ON", read.settings[StringPreferenceKey("darkMode")])
        assertEquals(false, read.settings[BooleanPreferenceKey("dynamicTheme")])
        assertNull(read.settings[StringPreferenceKey("innerTubeCookie")])
        assertNull(read.settings[StringPreferenceKey("github_releases_json")])
        assertNull(read.settings[StringPreferenceKey("spotify_library_playlists_cache")])
    }

    @Test
    fun theManifestIdentifiesAnAndroidZipAsNatunes() {
        val manifest = BackupArchiveLayout.manifest(databaseVersion = 39, createdAtMillis = 0L, appVersion = "test")
        assertEquals(false, BackupArchiveLayout.isForeign(manifest, hasSettingsJson = true))
    }
}
