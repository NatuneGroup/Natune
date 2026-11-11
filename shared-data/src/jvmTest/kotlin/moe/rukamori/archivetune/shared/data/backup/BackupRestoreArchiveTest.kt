package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayOutputStream
import java.util.zip.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory

class BackupRestoreArchiveTest {
    private val style = StringPreferenceKey("playerDesignStyle")
    private val catalog = StringPreferenceKey("spotify_sp_dc")
    private val stream = StringPreferenceKey("spotifyStreamSpDc")
    private val pool = StringPreferenceKey("poolTidalAccounts")
    private val device = StringPreferenceKey("scheduledBackupDirectoryUri")
    private fun libraryFixture(): ByteArray = javaClass.getResourceAsStream("/fixtures/schema36-synthetic-song.db")!!.use { it.readBytes() }
    private fun snapshot() = PreferenceSnapshot.of(style to "FLAMINGO", catalog to "catalog-fixture",
        stream to "stream-fixture", pool to "pool-fixture", device to "device-only")

    @Test fun settingsSelectionNeverContainsAccountsOrDeviceHandles() {
        val codec = JvmBackupArchiveCodec()
        val archive = codec.create(snapshot(), setOf(BackupCategory.SETTINGS))
        val prepared = codec.prepare(archive, setOf(BackupCategory.SETTINGS))
        assertEquals("FLAMINGO", prepared.settings[style])
        assertNull(prepared.settings[catalog])
        assertNull(prepared.settings[stream])
        assertNull(prepared.settings[pool])
        assertNull(prepared.settings[device])
    }
    @Test fun legacyAccountOnlyPreservesDistinctCredentialKeysWithoutChangingStyles() {
        val codec = JvmBackupArchiveCodec()
        val archive = zip("settings.xml" to """<ArchiveTuneBackup><Settings>
            <string name="spotify_sp_dc" value="catalog-fixture"/>
            <string name="spotifyStreamSpDc" value="stream-fixture"/>
            <string name="poolTidalAccounts" value="pool-fixture"/>
            <string name="playerDesignStyle" value="FLAMINGO"/>
            </Settings></ArchiveTuneBackup>""".toByteArray())
        val prepared = codec.prepare(archive, setOf(BackupCategory.ACCOUNT))
        assertNull(prepared.settings[style])
        assertEquals("catalog-fixture", prepared.settings[catalog])
        assertEquals("stream-fixture", prepared.settings[stream])
        assertEquals("pool-fixture", prepared.settings[pool])
    }
    @Test fun newXmlArchivesNeverContainSecretsEvenWhenAccountIsSelected() {
        for (selected in listOf(setOf(BackupCategory.SETTINGS), BackupCategory.entries.toSet(), setOf(BackupCategory.ACCOUNT))) {
            val library = if (BackupCategory.LIBRARY in selected) mapOf("song.db" to libraryFixture()) else emptyMap()
            val archive = JvmBackupArchiveCodec().create(credentialBackupSnapshot(), selected, library)
            assertCredentialFreePayload(archive)
            ZipInputStream(archive.inputStream()).use { zip ->
                assertEquals("settings.xml", zip.nextEntry.name)
                val xml = zip.readBytes()
                assertCredentialFreePayload(xml)
                if (BackupCategory.SETTINGS in selected) assertTrue(xml.decodeToString().contains("dark"))
                if (BackupCategory.LIBRARY in selected) {
                    assertEquals("song.db", zip.nextEntry.name)
                    val database = zip.readBytes()
                    assertContentEquals(libraryFixture(), database)
                    assertCredentialFreePayload(database)
                }
                assertNull(zip.nextEntry)
            }
        }
    }
    @Test fun inspectionOffersAccountOnlyWhenTheXmlActuallyContainsIt() = kotlinx.coroutines.test.runTest {
        val root = java.nio.file.Files.createTempDirectory("xml-backup-inspection")
        try {
            java.nio.file.Files.createDirectory(root.resolve("fonts"))
            val gateway = JvmBackupArchiveGateway(root, root.resolve("fonts"),
                object : moe.rukamori.archivetune.shared.domain.persistence.RestorePreferences {
                    override suspend fun snapshot(): PreferenceSnapshot = error("Unused")
                    override suspend fun replaceAll(snapshot: PreferenceSnapshot) = error("Unused")
                }, object : moe.rukamori.archivetune.shared.domain.persistence.RestoreLifecycle {
                    override suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T = error("Unused")
                    override suspend fun closeDatabase() = error("Unused")
                    override suspend fun reopenAndValidateDatabase() = error("Unused")
                }, object : BackupSnapshotSource {
                    override suspend fun libraryFiles(): Map<String, ByteArray> = error("Unused")
                    override suspend fun fontFiles(): Map<String, ByteArray> = error("Unused")
                })
            val fresh = JvmBackupArchiveCodec().create(credentialBackupSnapshot(), BackupCategory.entries.toSet(), mapOf("song.db" to libraryFixture()))
            assertEquals(setOf(moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory.LIBRARY,
                moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory.SETTINGS), gateway.inspect(fresh).categories)
            val legacy = zip("settings.xml" to """<ArchiveTuneBackup><Settings>
                <string name="spotify_sp_dc" value="legacy-cookie"/>
                </Settings></ArchiveTuneBackup>""".toByteArray())
            assertEquals(setOf(moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory.ACCOUNT), gateway.inspect(legacy).categories)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun validatesAllSelectedContentBeforeReturningAnyPlan() {
        val codec = JvmBackupArchiveCodec()
        assertFailsWith<IllegalArgumentException> {
            codec.prepare(zip("settings.xml" to "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///fixture'>]><x>&e;</x>".toByteArray()),
                setOf(BackupCategory.SETTINGS))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.prepare(zip("../settings.xml" to byteArrayOf(1)), setOf(BackupCategory.SETTINGS))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.prepare(zip("settings.xml" to "<ArchiveTuneBackup><Settings/></ArchiveTuneBackup>".toByteArray(),
                "fonts/bad.ttf" to "not-a-font".toByteArray()), setOf(BackupCategory.SETTINGS))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.prepare(zip("song.db" to "not-sqlite".toByteArray()), setOf(BackupCategory.LIBRARY))
        }
    }
    @Test fun historicalSettingsXmlRetainsUnicodeTypesAndSetEntries() {
        val xml = """<?xml version="1.0"?><ArchiveTuneBackup><Settings>
            <string name="playerDesignStyle" value="V4"/>
            <boolean name="enabled" value="true"/>
            <float name="historyDuration" value="3.5"/>
            <int name="historyDuration" value="4"/>
            <string-set name="tags"><item>日本語</item><item>عربي</item></string-set>
            </Settings></ArchiveTuneBackup>"""
        val prepared = JvmBackupArchiveCodec().prepare(zip("settings.xml" to xml.toByteArray()), setOf(BackupCategory.SETTINGS))
        assertEquals("V4", prepared.settings[style])
        assertEquals(3.5f, prepared.settings[FloatPreferenceKey("historyDuration")])
        assertEquals(4, prepared.settings[IntPreferenceKey("historyDuration")])
        assertEquals(setOf("日本語", "عربي"), prepared.settings[StringSetPreferenceKey("tags")])
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
}
