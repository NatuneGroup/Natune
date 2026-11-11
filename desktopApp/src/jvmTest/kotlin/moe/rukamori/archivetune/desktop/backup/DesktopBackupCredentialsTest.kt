package moe.rukamori.archivetune.desktop.backup

import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.backup.*
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.snapshotTo
import moe.rukamori.archivetune.shared.data.playlistexport.PlaylistExportOwner
import moe.rukamori.archivetune.shared.data.playlistimport.PlaylistImportOwner
import moe.rukamori.archivetune.shared.domain.backup.*
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportSource
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportSearch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.settings.*

class DesktopBackupCredentialsTest {
    private val secretKeys = settingsCredentialRoutes().keys + listOf(
        StringPreferenceKey("futureProviderPassword"), StringPreferenceKey("future_provider_access_token"),
        StringPreferenceKey("futureProviderApiKey"), StringPreferenceKey("futureProviderCookie"),
        StringPreferenceKey("futureProviderSecret"), StringPreferenceKey("futureProviderCredential"),
        StringPreferenceKey("futureProviderJwt"), StringPreferenceKey("futureProviderSession"),
        StringPreferenceKey("account.youtube.id"), StringPreferenceKey("accountName"),
    )

    @Test fun bothDesktopExportFormatsOmitLogicalVaultCredentialsEvenWithAccountSelected() = owner { _, settings, archives ->
        settings.edit { secretKeys.forEachIndexed { index, key -> this[key] = "synthetic-vault-secret-$index" } }
        // Prove the exporter received actual logical secrets, rather than a snapshot already stripped by the vault.
        secretKeys.forEach { assertTrue(settings.snapshot()[it]!!.startsWith("synthetic-vault-secret-")) }
        val selected = setOf(BackupDataCategory.ACCOUNT, BackupDataCategory.SETTINGS)
        for (archive in listOf(archives.create(selected), archives.createAndroid(selected))) {
            assertCleanArchive(archive)
            assertEquals(setOf(BackupDataCategory.SETTINGS), archives.inspect(archive).categories)
        }
    }

    @Test fun desktopFolderSyncWritesCredentialFreeArchiveBytesToTheActualFolder() = owner { root, settings, archives ->
        val folder = root.resolve("sync")
        settings.edit {
            secretKeys.forEachIndexed { index, key -> this[key] = "synthetic-vault-secret-$index" }
            this[REMOTE_FOLDER_URI_KEY] = "$LOCAL_FOLDER_SCHEME:$folder"
            this[GoogleDriveSyncRepository_OVERWRITE_KEY] = true
        }
        val sync = DesktopBackupSync(
            scope = this, settings = settings, archives = archives,
            playlistExport = PlaylistExportOwner(unused(PlaylistExportSource::class.java)),
            playlistImport = PlaylistImportOwner(unused(PlaylistImportLibrary::class.java), unused(PlaylistImportSearch::class.java)),
            playlistRows = { error("Unused playlist rows") }, report = { fail(it) }, nowMillis = { 1234L },
        )
        assertEquals(BackupSyncOutcome.COMPLETED, sync.syncGoogleDriveNow())
        val files = Files.list(folder).use { it.toList() }
        assertEquals(1, files.size)
        assertTrue(files.single().fileName.toString().endsWith(".backup"))
        val payload = Files.readAllBytes(files.single())
        assertCleanArchive(payload)
        assertEquals(setOf(BackupDataCategory.LIBRARY, BackupDataCategory.SETTINGS), archives.inspect(payload).categories)
        assertEquals(false, settings.snapshot()[LAST_SYNC_FAILED_KEY])
    }

    @Test fun desktopLegacyArchiveRestoresCredentialsThroughRealStagingAndVaultRouting() = owner { root, settings, archives ->
        val cookie = StringPreferenceKey("innerTubeCookie")
        val stream = StringPreferenceKey("spotifyStreamSpDc")
        settings.edit { this[cookie] = "current-cookie"; this[stream] = "current-stream" }
        val legacyJson = """{"formatVersion":1,"entries":[
            {"name":"innerTubeCookie","kind":"STRING","value":"legacy-cookie"},
            {"name":"spotifyStreamSpDc","kind":"STRING","value":"legacy-stream"},
            {"name":"theme","kind":"STRING","value":"light"}]}""".encodeToByteArray()
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in listOf(
                    PortableBackupFormat.MANIFEST to PortableBackupFormat.manifest(PortableBackupFormat.PLATFORM_DESKTOP, 0L),
                    PortableBackupFormat.SETTINGS to legacyJson,
                )) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
        assertEquals(setOf(BackupDataCategory.ACCOUNT, BackupDataCategory.SETTINGS), archives.inspect(archive).categories)
        assertTrue(archives.restore(archive, setOf(BackupDataCategory.ACCOUNT)).restartRequired)
        val pending = assertNotNull(DesktopPendingRestore.read(root))
        pending.applySettings(settings)
        assertEquals("legacy-cookie", settings.snapshot()[cookie])
        assertEquals("legacy-stream", settings.snapshot()[stream])
        assertEquals("dark", settings.snapshot()[StringPreferenceKey("theme")])
    }

    private fun assertCleanArchive(archive: ByteArray) {
        fun assertClean(bytes: ByteArray) {
            val raw = bytes.decodeToString()
            secretKeys.forEach { assertFalse(raw.contains(it.name), "Leaked key: ${it.name}") }
            assertFalse(raw.contains("synthetic-vault-secret-"), "Leaked secret value")
            assertFalse(raw.contains("secure_store_"), "Leaked vault reference")
        }
        assertClean(archive)
        var settingsFound = false
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                assertClean(bytes)
                if (entry.name == PortableBackupFormat.SETTINGS || entry.name == "settings.xml") {
                    settingsFound = true
                    assertTrue(bytes.decodeToString().contains("dark"))
                }
            }
        }
        assertTrue(settingsFound)
    }

    private fun owner(block: suspend CoroutineScope.(Path, VaultSettingsStore, DesktopBackupArchiveGateway) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("desktop-backup-credentials")
        val ordinary = MemorySettings()
        val vault = object : SecureCredentialStore {
            val entries = mutableMapOf<CredentialKey, String>()
            override suspend fun read(key: CredentialKey): String? = entries[key]
            override suspend fun write(key: CredentialKey, value: String) { entries[key] = value }
            override suspend fun remove(key: CredentialKey) { entries.remove(key) }
        }
        val settings = VaultSettingsStore.open(ordinary, vault, { UUID.randomUUID().toString() })
        val database = openPortableStateDatabase(root.resolve("live-state.db").toString())
        try {
            settings.edit { this[StringPreferenceKey("theme")] = "dark" }
            val archives = DesktopBackupArchiveGateway(settings, { database.snapshotTo(it) }, root, { 0L })
            block(root, settings, archives)
        } finally {
            database.close()
            settings.close()
            root.toFile().deleteRecursively()
        }
    }

    private class MemorySettings : SettingsStore {
        override val snapshots = MutableStateFlow<PreferenceSnapshot?>(PreferenceSnapshot.EMPTY)
        override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
            val before = snapshots.value!!
            val after = before.toEditor().apply(change).snapshot()
            snapshots.value = after
            return SettingsChange(before, after)
        }
    }

    /** These unrelated ports must never be invoked by sync; an unexpected call fails the test. */
    private fun <T> unused(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        throw AssertionError("Unexpected playlist call: ${method.name}")
    })
}
