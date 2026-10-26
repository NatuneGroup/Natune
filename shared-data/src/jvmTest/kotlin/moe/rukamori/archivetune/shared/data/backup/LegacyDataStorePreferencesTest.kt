/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayOutputStream
import java.util.zip.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.domain.settings.*

/** Synthetic AndroidX-written files and public-proto vectors, NOT historical user files. */
class LegacyDataStorePreferencesTest {
    @Test fun readsSyntheticFileWrittenByRealAndroidxDataStore121() = runTest {
        val root = Files.createTempDirectory("real-datastore-fixture")
        val file = root.resolve("fixture.preferences_pb").toFile()
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            store.edit {
                it[stringPreferencesKey("unicode")] = "日本語 👩‍👩‍👦"
                it[booleanPreferencesKey("boolean")] = true
                it[intPreferencesKey("integer")] = -7
                it[longPreferencesKey("long")] = Long.MAX_VALUE
                it[floatPreferencesKey("float")] = -0.0f
                it[doublePreferencesKey("double")] = 1.25
                it[stringSetPreferencesKey("set")] = setOf("عربي", "日本語")
            }
            job.cancelAndJoin()
            val values = LegacyDataStorePreferences.read(file.readBytes())
            assertEquals("日本語 👩‍👩‍👦", values[StringPreferenceKey("unicode")])
            assertEquals(true, values[BooleanPreferenceKey("boolean")])
            assertEquals(-7, values[IntPreferenceKey("integer")])
            assertEquals(Long.MAX_VALUE, values[LongPreferenceKey("long")])
            assertEquals((-0.0f).toBits(), values[FloatPreferenceKey("float")]!!.toBits())
            assertEquals(1.25, values[DoublePreferenceKey("double")])
            assertEquals(setOf("عربي", "日本語"), values[StringSetPreferenceKey("set")])
        } finally { job.cancelAndJoin(); root.toFile().deleteRecursively() }
    }
    private fun varint(number: Long): ByteArray = ByteArrayOutputStream().also {
        var value = number
        do {
            val next = value and 127
            value = value ushr 7
            it.write((next or if (value != 0L) 128L else 0L).toInt())
        } while (value != 0L)
    }.toByteArray()
    private fun blob(tag: Int, bytes: ByteArray): ByteArray = varint(tag.toLong()) + varint(bytes.size.toLong()) + bytes
    private fun string(tag: Int, text: String) = blob(tag, text.encodeToByteArray())
    private fun entry(name: String, value: ByteArray) = blob(10, string(10, name) + blob(18, value))
    private fun fixed(tag: Int, bits: Long, count: Int) = varint(tag.toLong()) + ByteArray(count) { (bits ushr (it * 8)).toByte() }
    @Test fun retainsUnicodeAllSupportedTypesAndLongPrecision() {
        val bytes = entry("text", string(42, "日本語 👩‍👩‍👦")) +
            entry("boolean", byteArrayOf(8, 1)) + entry("integer", byteArrayOf(24) + varint(-7)) +
            entry("long", byteArrayOf(32) + varint(Long.MAX_VALUE)) +
            entry("float", fixed(21, 3.5f.toBits().toLong(), 4)) +
            entry("double", fixed(57, 1.25.toBits(), 8)) +
            entry("set", blob(50, string(10, "عربي") + string(10, "日本語")))
        val values = LegacyDataStorePreferences.read(bytes)
        assertEquals("日本語 👩‍👩‍👦", values[StringPreferenceKey("text")])
        assertEquals(true, values[BooleanPreferenceKey("boolean")])
        assertEquals(-7, values[IntPreferenceKey("integer")])
        assertEquals(Long.MAX_VALUE, values[LongPreferenceKey("long")])
        assertEquals(3.5f, values[FloatPreferenceKey("float")])
        assertEquals(1.25, values[DoublePreferenceKey("double")])
        assertEquals(setOf("عربي", "日本語"), values[StringSetPreferenceKey("set")])
    }
    @Test fun categorySelectiveHistoricalPbArchiveNeverCopiesForeignDeviceStateOrOtherAccounts() {
        val pb = entry("playerDesignStyle", string(42, "FLAMINGO")) +
            entry("spotify_sp_dc", string(42, "catalog-fixture")) +
            entry("spotifyStreamSpDc", string(42, "stream-fixture")) +
            entry("spotifyStreamDeviceId", string(42, "foreign-device"))
        val prepared = JvmBackupArchiveCodec().prepare(zip("settings.preferences_pb", pb), setOf(BackupCategory.SETTINGS))
        assertEquals("FLAMINGO", prepared.settings[StringPreferenceKey("playerDesignStyle")])
        assertEquals(1, prepared.settings.keys.size)
        val account = JvmBackupArchiveCodec().prepare(zip("settings.preferences_pb", pb), setOf(BackupCategory.ACCOUNT))
        assertEquals("catalog-fixture", account.settings[StringPreferenceKey("spotify_sp_dc")])
        assertEquals("stream-fixture", account.settings[StringPreferenceKey("spotifyStreamSpDc")])
        assertNull(account.settings[StringPreferenceKey("spotifyStreamDeviceId")])
    }
    @Test fun rejectsUnknownByteArrayDuplicateKeysAndTruncatedWireInsteadOfDroppingValues() {
        val valid = entry("key", string(42, "value"))
        for (bad in listOf(valid + valid, valid.copyOf(valid.size - 1),
            entry("unsupported", blob(66, byteArrayOf(1))), byteArrayOf(2, 0))) {
            assertFailsWith<IllegalArgumentException> { LegacyDataStorePreferences.read(bad) }
        }
    }
    private fun zip(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }
    }.toByteArray()
}
