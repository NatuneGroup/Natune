/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class VaultSettingsStoreTest {
    @Test fun actualDiskContainsOnlyReferencesAndRehydratesLogicalAccountValuesInSeparateScopes() = disk { path ->
        val vault = TestSettingsVault()
        val credentials = listOf(
            AiApiKeyKey to "test-only-private-ai",
            SpotifyAccessTokenKey to "test-only-private-catalog",
            SpotifyStreamAccessTokenKey to "test-only-private-stream",
            TidalAccessTokenKey to "test-only-private-personal",
            StringPreferenceKey("poolTidalAccounts") to "test-only-private-pool",
        )
        val store = VaultTestOwner.open(path, vault)
        try {
            store.edit {
                credentials.forEach { (key, value) -> this[key] = value }
                this[AudioNormalizationKey] = false
            }
            val bytes = Files.readAllBytes(path).toString(Charsets.UTF_8)
            credentials.forEach { (key, value) ->
                assertFalse(bytes.contains(value))
                assertNull(store.ordinary.snapshot()[key])
                assertEquals(value, store.snapshot()[key])
            }
            assertEquals(setOf(CredentialScope.PERSONAL, CredentialScope.SPOTIFY_CATALOG,
                CredentialScope.SPOTIFY_STREAMING, CredentialScope.PLAYBACK_POOL), vault.entries.keys.map { it.scope }.toSet())
            assertTrue(store.snapshot().keys.none { it.name.startsWith("secure_store_") })
        } finally { store.close() }
        val reopened = VaultTestOwner.open(path, vault)
        try {
            // This logical snapshot is the input to M7's existing ACCOUNT-selecting backup codec.
            val logicalExportInput = reopened.snapshot()
            credentials.forEach { (key, value) -> assertEquals(value, logicalExportInput[key]) }
            assertEquals(false, logicalExportInput[AudioNormalizationKey])
            assertTrue(logicalExportInput.keys.none { it.name.startsWith("secure_store_") })
        } finally { reopened.close() }
    }

    @Test fun unavailableVaultNeverFallsBackAndMixedEditCannotPersistOrPrompt() = disk { path ->
        val vault = TestSettingsVault().apply { unavailable = true }
        val store = VaultTestOwner.open(path, vault)
        try {
            val controller = SettingsController(store, store.snapshot(), false)
            controller.set(AudioNormalizationKey, false)
            val previousBytes = Files.readAllBytes(path)
            val failure = assertFailsWith<SettingsVaultUnavailable> {
                controller.edit { this[AiApiKeyKey] = "test-only-private-new"; this[UiScaleFactorKey] = 1.2f }
            }
            assertFalse(failure.toString().contains("sensitive"))
            // JVM coroutine stack recovery can attach the original sanitized exception as cause.
            assertTrue(failure.cause == null || failure.cause is SettingsVaultUnavailable)
            assertNull(store.snapshot()[AiApiKeyKey])
            assertNull(store.snapshot()[UiScaleFactorKey])
            assertFalse(controller.restart.value.visible)
            assertContentEquals(previousBytes, Files.readAllBytes(path))
        } finally { store.close() }
        val reopened = VaultTestOwner.open(path, vault)
        try { assertEquals(false, reopened.snapshot()[AudioNormalizationKey]); assertNull(reopened.snapshot()[AiApiKeyKey]) }
        finally { reopened.close() }
    }

    @Test fun actualMetadataMoveFailurePreservesOldVaultValueForRestartAndCleansStaging() = disk { path ->
        val vault = TestSettingsVault()
        val store = VaultTestOwner.open(path, vault)
        store.edit { this[AiApiKeyKey] = "test-only-private-old" }
        val oldBytes = Files.readAllBytes(path)
        val oldEntries = vault.entries.toMap()
        val controller = SettingsController(store, store.snapshot(), false)
        try {
            Files.delete(path); Files.createDirectory(path)
            assertFailsWith<java.io.IOException> {
                controller.edit { this[AiApiKeyKey] = "test-only-private-new"; this[UiScaleFactorKey] = 1.2f }
            }
            assertEquals("test-only-private-old", store.snapshot()[AiApiKeyKey])
            assertEquals(oldEntries, vault.entries)
            assertFalse(controller.restart.value.visible)
        } finally {
            Files.delete(path); Files.write(path, oldBytes)
            store.close()
        }
        val reopened = VaultTestOwner.open(path, vault)
        try { assertEquals("test-only-private-old", reopened.snapshot()[AiApiKeyKey]); assertNull(reopened.snapshot()[UiScaleFactorKey]) }
        finally { reopened.close() }
    }

    @Test fun genericDiskCannotAcceptKnownSecretsAndMissingVaultEntryIsNotAnEmptyAccount() = disk { path ->
        val ordinary = DesktopSettingsStore.open(path)
        try {
            assertFailsWith<IllegalArgumentException> { ordinary.edit { this[OpenRouterApiKeyKey] = "test-only-private" } }
            assertTrue(ordinary.snapshot().keys.isEmpty())
            assertFalse(Files.exists(path))
        } finally { ordinary.close() }
        val vault = TestSettingsVault()
        val owner = VaultTestOwner.open(path, vault)
        try { owner.edit { this[OpenRouterApiKeyKey] = "test-only-private" } } finally { owner.close() }
        val bytes = Files.readAllBytes(path)
        vault.entries.clear()
        assertFailsWith<SettingsVaultUnavailable> { VaultTestOwner.open(path, vault) }
        assertContentEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun logicalRestoreAndRemovalNeverWriteSecretsToDiskOrLeaveReferencedOldSecrets() = disk { path ->
        val vault = TestSettingsVault()
        val owner = VaultTestOwner.open(path, vault)
        try {
            owner.edit { this[AiApiKeyKey] = "test-only-private-old"; this[OpenRouterModelKey] = "old-model" }
            owner.settings.replaceAll(PreferenceSnapshot.of(DeeplApiKeyKey to "test-only-private-restored", AiProviderKey to "DEEPL"))
            assertNull(owner.snapshot()[AiApiKeyKey])
            assertNull(owner.snapshot()[OpenRouterModelKey])
            assertEquals(listOf("test-only-private-restored"), vault.entries.values.toList())
            assertFalse(Files.readAllBytes(path).toString(Charsets.UTF_8).contains("test-only-private"))
            owner.settings.replaceAll(PreferenceSnapshot.EMPTY)
            assertTrue(owner.ordinary.snapshot().keys.isEmpty())
            assertTrue(vault.entries.isEmpty())
        } finally { owner.close() }
    }

    @Test fun existingPreferenceCredentialsMigrateBeforeAdapterExposureAndReferencesCannotBeImported() = runBlocking {
        // Models the baseline DataStore snapshot; this fixture is never a production fallback.
        val rawState = MutableStateFlow<PreferenceSnapshot?>(PreferenceSnapshot.of(AiApiKeyKey to "test-only-private-legacy"))
        val ordinary = object : SettingsStore {
            override val snapshots = rawState
            override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
                val before = requireNotNull(rawState.value)
                val after = before.toEditor().apply(change).snapshot()
                rawState.value = after
                return SettingsChange(before, after)
            }
        }
        val vault = TestSettingsVault()
        val adapter = VaultSettingsStore.open(ordinary, vault, { UUID.randomUUID().toString() })
        try {
            assertNull(rawState.value?.get(AiApiKeyKey))
            assertEquals("test-only-private-legacy", adapter.snapshot()[AiApiKeyKey])
            assertFailsWith<IllegalArgumentException> { adapter.replaceAll(requireNotNull(rawState.value)) }
            adapter.edit { this[AiApiKeyKey] = "test-only-private-replacement" }
            assertEquals(listOf("test-only-private-replacement"), vault.entries.values.toList())
        } finally { adapter.close() }
    }

    @Test fun collidingReferenceFailsClosedWithoutOverwritingPreviousCredential() = disk { path ->
        val vault = TestSettingsVault()
        val ordinary = DesktopSettingsStore.open(path)
        val adapter = VaultSettingsStore.open(ordinary, vault, { "deliberate-test-collision" })
        try {
            adapter.edit { this[AiApiKeyKey] = "test-only-private-original" }
            assertFailsWith<SettingsVaultUnavailable> { adapter.edit { this[AiApiKeyKey] = "test-only-private-replacement" } }
            assertEquals(listOf("test-only-private-original"), vault.entries.values.toList())
            assertEquals("test-only-private-original", adapter.snapshot()[AiApiKeyKey])
        } finally { adapter.close(); ordinary.close() }
    }

    private fun disk(test: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("m6-explicit-test-vault-")
        try { test(directory.resolve("settings.bin")) } finally { directory.toFile().deleteRecursively() }
    }
}
