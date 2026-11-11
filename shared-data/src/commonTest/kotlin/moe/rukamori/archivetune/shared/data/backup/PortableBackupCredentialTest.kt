package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.backup.*
import moe.rukamori.archivetune.shared.domain.settings.*

internal val backupCredentialNames = listOf(
    "innerTubeCookie", "spotify_sp_dc", "spotify_sp_key", "deezerArl", "qqMusicMusickey", "lyprioSigningKey",
    "future-cookie", "future_token", "futurePassword", "futureSecret", "future_api_key",
    "futureCredential", "futureJwt", "futureSession", "SpotifyStreamSpDc", "spotify_stream_refresh_grant",
    "account.youtube.cookies", "account.youtube.id", "secure_store_youtube", "accountName", "poolTidalAccounts",
)

internal fun credentialBackupSnapshot(): PreferenceSnapshot = PreferenceSnapshot.of(
    *(backupCredentialNames.mapIndexed { index, name -> StringPreferenceKey(name) to "synthetic-private-$index" } + listOf(
        StringPreferenceKey("theme") to "dark",
        BooleanPreferenceKey("use_pool_accounts") to true,
        StringSetPreferenceKey("futureSecretSet") to setOf("synthetic-private-set"),
    )).toTypedArray(),
)

internal fun assertCredentialFreePayload(bytes: ByteArray) {
    val raw = bytes.decodeToString()
    backupCredentialNames.forEach { assertFalse(raw.contains(it), "Leaked key: $it") }
    assertFalse(raw.contains("futureSecretSet"))
    assertFalse(raw.contains("synthetic-private-"), "Leaked secret value")
}

class PortableBackupCredentialTest {
    @Test fun exportEncoderExcludesSecretsFromAnUnfilteredSnapshot() {
        val bytes = PortableBackupFormat.encodeSettings(credentialBackupSnapshot())
        assertCredentialFreePayload(bytes)
        val decoded = PortableBackupFormat.decodeSettings(bytes)
        assertEquals(setOf(StringPreferenceKey("theme"), BooleanPreferenceKey("use_pool_accounts")), decoded.keys.toSet())
        assertEquals("dark", decoded[StringPreferenceKey("theme")])
    }

    @Test fun bothPortablePlatformArchivesContainOnlyOrdinarySettings() {
        for (platform in listOf(PortableBackupFormat.PLATFORM_IOS, PortableBackupFormat.PLATFORM_DESKTOP)) {
            val archive = PortableBackupArchive.create(platform, null, credentialBackupSnapshot(), nowMillis = 0L)
            assertCredentialFreePayload(archive)
            val entries = PortableZip.read(archive, 16, { PortableBackupFormat.MAX_SETTINGS_BYTES }, PortableBackupFormat.MAX_TOTAL_BYTES)
            assertEquals(setOf(PortableBackupFormat.MANIFEST, PortableBackupFormat.SETTINGS), entries.map { it.name }.toSet())
            entries.forEach { assertCredentialFreePayload(it.data) }
            assertEquals(setOf(BackupDataCategory.SETTINGS), PortableBackupArchive.parse(archive).categories)
        }
    }

    @Test fun sharedSyncPayloadUsesTheRealPortableWriterAndOmitsAccountSelection() = runTest {
        var chosen: Set<BackupDataCategory>? = null
        val archives = object : BackupArchiveGateway {
            override suspend fun create(selected: Set<BackupDataCategory>): ByteArray {
                chosen = selected
                // The same common writer the iOS gateway calls, with deliberately unfiltered settings.
                return PortableBackupArchive.create(PortableBackupFormat.PLATFORM_IOS, null, credentialBackupSnapshot(), 0L)
            }
            override suspend fun inspect(archive: ByteArray): BackupArchiveInspection = error("Unused")
            override suspend fun restore(archive: ByteArray, selected: Set<BackupDataCategory>): BackupRestoreOutcome = error("Unused")
        }
        val payload = createSyncBackup(archives)
        assertEquals(setOf(BackupDataCategory.LIBRARY, BackupDataCategory.SETTINGS), chosen)
        PortableZip.read(payload, 16, { PortableBackupFormat.MAX_SETTINGS_BYTES }, PortableBackupFormat.MAX_TOTAL_BYTES)
            .forEach { assertCredentialFreePayload(it.data) }
    }

    @Test fun legacyArchiveCredentialsSurviveParseStagingAndSelectedRestore() {
        // A literal version-1 payload: never generate a legacy credential fixture with the export encoder.
        val oldSettings = """{"formatVersion":1,"entries":[
            {"name":"innerTubeCookie","kind":"STRING","value":"legacy-cookie"},
            {"name":"spotifyStreamSpDc","kind":"STRING","value":"legacy-stream"},
            {"name":"theme","kind":"STRING","value":"dark"}]}""".encodeToByteArray()
        val oldArchive = PortableZip.write(listOf(
            PortableBackupFormat.MANIFEST to PortableBackupFormat.manifest(PortableBackupFormat.PLATFORM_IOS, 0L),
            PortableBackupFormat.SETTINGS to oldSettings,
        ), 0L)
        val parsed = PortableBackupArchive.parse(oldArchive)
        assertEquals(setOf(BackupDataCategory.ACCOUNT, BackupDataCategory.SETTINGS), parsed.categories)
        val selected = settingsSubset(parsed.settings!!, setOf(BackupDataCategory.ACCOUNT))!!
        val staged = PortableBackupFormat.decodeSettings(PortableBackupFormat.encodeRestoreSettings(selected))
        val current = PreferenceSnapshot.of(StringPreferenceKey("innerTubeCookie") to "previous-cookie",
            StringPreferenceKey("theme") to "light", StringPreferenceKey("spotifyStreamDeviceId") to "local-device")
        val restored = mergedRestoredSettings(current, staged, setOf(BackupCategory.ACCOUNT))
        assertEquals("legacy-cookie", restored[StringPreferenceKey("innerTubeCookie")])
        assertEquals("legacy-stream", restored[StringPreferenceKey("spotifyStreamSpDc")])
        assertEquals("light", restored[StringPreferenceKey("theme")])
        assertEquals("local-device", restored[StringPreferenceKey("spotifyStreamDeviceId")])
    }
}
