package app.natune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import app.natune.shared.domain.backup.BackupArchiveException
import app.natune.shared.domain.settings.BooleanPreferenceKey
import app.natune.shared.domain.settings.FloatPreferenceKey
import app.natune.shared.domain.settings.IntPreferenceKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.StringPreferenceKey
import app.natune.shared.domain.settings.StringSetPreferenceKey

class BackupSettingsReaderTest {
    @Test
    fun readsTheNatuneLayoutExactlyAsTheStrictCodecWritesIt() {
        val written = PreferenceSnapshot.EMPTY.toEditor().apply {
            this[StringPreferenceKey("darkMode")] = "ON"
            this[BooleanPreferenceKey("dynamicTheme")] = false
            this[FloatPreferenceKey("lyricsTextSize")] = 26.5f
            this[StringSetPreferenceKey("local_songs_included_folders")] = setOf("/Music", "/Download")
        }.snapshot()

        val read = BackupSettingsReader.read(PortableBackupFormat.encodeRestoreSettings(written), foreign = false)

        assertEquals(emptyList(), read.skipped)
        assertEquals("ON", read.settings[StringPreferenceKey("darkMode")])
        assertEquals(false, read.settings[BooleanPreferenceKey("dynamicTheme")])
        assertEquals(26.5f, read.settings[FloatPreferenceKey("lyricsTextSize")])
        assertEquals(setOf("/Music", "/Download"), read.settings[StringSetPreferenceKey("local_songs_included_folders")])
    }

    @Test
    fun readsMetrolistEntriesAndKeepsOnlySettingsThisBuildStoresWithTheSameKind() {
        // The shape of a real Metrolist settings.json: key/type/value, every value a string.
        val metrolist = """
            {"entries":[
              {"key":"audioQuality","type":"STRING","value":"HIGH"},
              {"key":"crossfadeEnabled","type":"BOOLEAN","value":"true"},
              {"key":"playerBackgroundStyle","type":"INT","value":"3"},
              {"key":"floatingNavBar","type":"BOOLEAN","value":"true"},
              {"key":"dynamicTheme","type":"BOOLEAN","value":"yes"}
            ]}
        """.trimIndent().encodeToByteArray()

        val read = BackupSettingsReader.read(metrolist, foreign = true)

        assertEquals("HIGH", read.settings[StringPreferenceKey("audioQuality")])
        assertEquals(true, read.settings[BooleanPreferenceKey("crossfadeEnabled")])
        // Natune stores playerBackgroundStyle as a STRING, so Metrolist's INT is another setting with the same name.
        assertNull(read.settings[IntPreferenceKey("playerBackgroundStyle")])
        assertEquals(
            mapOf(
                "playerBackgroundStyle" to SkippedSetting.Reason.WRONG_TYPE,
                "floatingNavBar" to SkippedSetting.Reason.UNKNOWN_SETTING,
                "dynamicTheme" to SkippedSetting.Reason.UNREADABLE,
            ),
            read.skipped.associate { it.name to it.reason },
        )
    }

    @Test
    fun oneBadEntryIsSkippedWithoutLosingTheRest() {
        val damaged = """
            {"formatVersion":1,"entries":[
              {"name":"darkMode","kind":"STRING","value":"OFF"},
              {"name":"lyricsTextSize","kind":"FLOAT","value":"not a number"},
              {"name":"enablePaxsenixSpotifyLyrics","kind":"BOOLEAN","value":true},
              {"name":"darkMode","kind":"STRING","value":"ON"},
              {"kind":"BOOLEAN","value":true},
              "garbage"
            ]}
        """.trimIndent().encodeToByteArray()

        val read = BackupSettingsReader.read(damaged, foreign = false)

        assertEquals("OFF", read.settings[StringPreferenceKey("darkMode")])
        assertEquals(
            listOf(
                SkippedSetting.Reason.UNREADABLE,
                SkippedSetting.Reason.RETIRED,
                SkippedSetting.Reason.DUPLICATE,
                SkippedSetting.Reason.UNREADABLE,
                SkippedSetting.Reason.UNREADABLE,
            ),
            read.skipped.map { it.reason },
        )
    }

    @Test
    fun aFileWithoutEntriesIsCorrupted() {
        assertFailsWith<BackupArchiveException> { BackupSettingsReader.read("<xml/>".encodeToByteArray(), foreign = false) }
        assertFailsWith<BackupArchiveException> { BackupSettingsReader.read("""{"settings":1}""".encodeToByteArray(), foreign = true) }
    }
}
