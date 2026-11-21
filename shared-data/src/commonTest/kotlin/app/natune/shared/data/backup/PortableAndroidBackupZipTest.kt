package app.natune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import app.natune.shared.domain.backup.BackupArchiveException
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.StringPreferenceKey

class PortableAndroidBackupZipTest {
    private val sqlite = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(84)

    private fun settings(vararg values: Pair<String, String>): ByteArray =
        PortableBackupFormat.encodeRestoreSettings(
            PreferenceSnapshot.EMPTY.toEditor().apply { values.forEach { (k, v) -> this[StringPreferenceKey(k)] = v } }.snapshot(),
        )

    @Test
    fun anAndroidZipBackupBringsItsLibraryAndSettings() {
        val archive = PortableZip.write(
            listOf(
                "backup.json" to """{"format":"natune.android.backup","formatVersion":1,"platform":"ANDROID"}""".encodeToByteArray(),
                "settings.json" to settings("darkMode" to "ON"),
                "song.db" to sqlite,
            ),
            nowMillis = 1_795_219_200_000L,
        )

        val payload = PortableAndroidBackup.parse(archive)

        assertEquals(setOf("song.db"), payload.files.keys)
        assertEquals("ON", payload.settings[StringPreferenceKey("darkMode")])
    }

    @Test
    fun anotherAppsZipBringsOnlyItsSettings() {
        val archive = PortableZip.write(
            listOf(
                "backup.json" to """{"platform":"ANDROID","databaseVersion":65}""".encodeToByteArray(),
                "settings.json" to """{"entries":[{"key":"audioQuality","type":"STRING","value":"HIGH"}]}""".encodeToByteArray(),
                "song.db" to sqlite,
            ),
            nowMillis = 1_795_219_200_000L,
        )

        val payload = PortableAndroidBackup.parse(archive)

        assertTrue(payload.files.isEmpty())
        assertEquals("HIGH", payload.settings[StringPreferenceKey("audioQuality")])
    }

    @Test
    fun twoSettingsPayloadsAreRefused() {
        val archive = PortableZip.write(
            listOf("settings.json" to settings("darkMode" to "ON"), "settings.preferences_pb" to ByteArray(1), "song.db" to sqlite),
            nowMillis = 1_795_219_200_000L,
        )
        assertFailsWith<BackupArchiveException> { PortableAndroidBackup.parse(archive) }
    }
}
