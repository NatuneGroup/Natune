package moe.rukamori.archivetune.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackupArchiveRepositoryTest {
    @Test
    fun producedArchiveOmitsEveryAccountAndCredentialKeyEvenWithAccountSelected() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("playerDesignStyle") to "FLAMINGO",
            booleanPreferencesKey("use_pool_accounts") to true,
            stringPreferencesKey("customFontName") to "My font",
            stringPreferencesKey("scheduledBackupDirectoryUri") to "synthetic-private-folder",
        )
        val credentialKeys = BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS + listOf(
            "futureProviderCookie", "future_provider_access_token", "futureProviderApiKey", "futureProviderPassword",
            "account.youtube.cookies", "spotify_stream_refresh_grant", "SpotifyStreamSession",
            "secure_store_youtube", "lyprioSigningKey", "newProviderCredential", "future-provider-secret",
        )
        credentialKeys.forEach { preferences[stringPreferencesKey(it)] = "synthetic-credential" }
        for (categories in listOf(BackupExportPolicy.DEFAULT_CATEGORIES, BackupArchiveCategory.entries.toSet())) {
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                zip.putNextEntry(ZipEntry(BackupArchiveRepository.SETTINGS_XML_FILENAME))
                writeBackupSettingsToXml(zip, preferences, categories)
                zip.closeEntry()
            }
            ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
                assertEquals("settings.xml", zip.nextEntry.name)
                val xml = zip.readBytes()
                assertFalse(xml.decodeToString().contains("synthetic-credential"))
                assertFalse(xml.decodeToString().contains("synthetic-private-folder"))
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()).getElementsByTagName("Settings").item(0).childNodes
                val exported = (0 until nodes.length).mapNotNull { nodes.item(it).attributes?.getNamedItem("name")?.nodeValue }.toSet()
                assertEquals(setOf("playerDesignStyle", "use_pool_accounts", "customFontName"), exported)
                credentialKeys.forEach { assertFalse(it, it in exported) }
                assertNull(zip.nextEntry)
            }
        }
    }

    @Test
    fun newSettingsPayloadHidesAccountWhileLegacyPayloadStillOffersIt() {
        val output = ByteArrayOutputStream()
        writeBackupSettingsToXml(output, mutablePreferencesOf(
            stringPreferencesKey("playerDesignStyle") to "FLAMINGO",
            stringPreferencesKey("innerTubeCookie") to "synthetic-credential",
        ), BackupArchiveCategory.entries.toSet())
        assertEquals(setOf(BackupArchiveCategory.SETTINGS), inspectBackupSettings("settings.xml", output.toByteArray().inputStream()))
        val legacy = """<ArchiveTuneBackup><Settings>
            <string name="playerDesignStyle" value="FLAMINGO"/>
            <string name="innerTubeCookie" value="legacy-cookie"/>
            </Settings></ArchiveTuneBackup>""".byteInputStream()
        assertEquals(setOf(BackupArchiveCategory.SETTINGS, BackupArchiveCategory.ACCOUNT), inspectBackupSettings("settings.xml", legacy))
    }

    @Test
    fun actualArchiveInspectionHidesAccountForNewExportsAndOffersLegacyCredentials() = kotlinx.coroutines.runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val file = File.createTempFile("backup-inspection", ".backup", context.cacheDir)
        try {
            fun archive(legacy: Boolean) {
                ZipOutputStream(file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("settings.xml"))
                    if (legacy) {
                        zip.write("""<ArchiveTuneBackup><Settings><string name="innerTubeCookie" value="legacy-cookie"/></Settings></ArchiveTuneBackup>""".toByteArray())
                    } else {
                        writeBackupSettingsToXml(zip, mutablePreferencesOf(
                            stringPreferencesKey("playerDesignStyle") to "FLAMINGO",
                            stringPreferencesKey("innerTubeCookie") to "synthetic-credential",
                        ), BackupArchiveCategory.entries.toSet())
                    }
                    zip.closeEntry()
                }
            }
            archive(false)
            val fresh = validateBackupArchive(context, android.net.Uri.fromFile(file))
            assertTrue(fresh.isValid)
            assertEquals(setOf(moe.rukamori.archivetune.viewmodels.BackupCategory.SETTINGS), fresh.availableCategories)
            archive(true)
            val old = validateBackupArchive(context, android.net.Uri.fromFile(file))
            assertTrue(old.isValid)
            assertEquals(setOf(moe.rukamori.archivetune.viewmodels.BackupCategory.ACCOUNT), old.availableCategories)
        } finally { file.delete() }
    }

    @Test
    fun unattendedDefaultsContainOnlyLibraryAndSettings() {
        assertEquals(setOf(BackupArchiveCategory.LIBRARY, BackupArchiveCategory.SETTINGS), BackupExportPolicy.DEFAULT_CATEGORIES)
        assertFalse(BackupExportPolicy.includes("innerTubeCookie", BackupExportPolicy.DEFAULT_CATEGORIES))
        assertTrue(BackupExportPolicy.includes("playerDesignStyle", BackupExportPolicy.DEFAULT_CATEGORIES))
    }

    @Test
    fun osBackupAndDeviceTransferExcludeAllCredentialFilesAndKeepOtherDataEligible() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val expected = setOf(
            "file" to "datastore/settings.preferences_pb",
            "sharedpref" to "kmp_secure_credentials.xml",
            "root" to "app_webview",
            "file" to "telegram/db",
            "file" to "datastore/listener_presence.preferences_pb",
        )
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/res/xml/$name"))
            val sections = if (name == "backup_rules.xml") listOf(document.documentElement) else
                listOf(document.getElementsByTagName("cloud-backup").item(0), document.getElementsByTagName("device-transfer").item(0))
            for (section in sections) {
                val nodes = section.childNodes
                val exclusions = (0 until nodes.length).mapNotNull { index ->
                    val node = nodes.item(index)
                    if (node.nodeName != "exclude") null else
                        node.attributes.getNamedItem("domain").nodeValue to node.attributes.getNamedItem("path").nodeValue.removePrefix("./")
                }.toSet()
                assertTrue("$name exclusions: $exclusions", exclusions.containsAll(expected))
                assertFalse("All files must remain eligible except explicit exclusions", exclusions.any { it.second == "." || it.second.isEmpty() })
                assertFalse("song.db" in exclusions.map { it.second })
                assertFalse("custom_fonts" in exclusions.map { it.second })
            }
        }
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0).attributes
        assertEquals("true", application.getNamedItem("android:allowBackup").nodeValue)
        assertEquals("@xml/backup_rules", application.getNamedItem("android:fullBackupContent").nodeValue)
        assertEquals("@xml/data_extraction_rules", application.getNamedItem("android:dataExtractionRules").nodeValue)
    }
}
