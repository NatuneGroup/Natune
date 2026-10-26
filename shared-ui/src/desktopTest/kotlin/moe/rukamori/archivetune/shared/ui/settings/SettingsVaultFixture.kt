/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Path
import java.util.UUID
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.settings.*

/** Explicit test vault, NOT a production memory/plaintext fallback or native-vault evidence. */
internal class TestSettingsVault : SecureCredentialStore {
    val entries = mutableMapOf<CredentialKey, String>()
    var unavailable = false
    override suspend fun read(key: CredentialKey): String? { available(); return entries[key] }
    override suspend fun write(key: CredentialKey, value: String) { available(); entries[key] = value }
    override suspend fun remove(key: CredentialKey) { available(); entries.remove(key) }
    private fun available() { check(!unavailable) { "test-only-sensitive-native-error" } }
}

internal class VaultTestOwner private constructor(
    val ordinary: DesktopSettingsStore,
    val settings: VaultSettingsStore,
) : SettingsStore by settings {
    suspend fun snapshot() = settings.snapshot()
    suspend fun close() { try { settings.close() } finally { ordinary.close() } }

    companion object {
        suspend fun open(path: Path, vault: SecureCredentialStore): VaultTestOwner {
            val ordinary = DesktopSettingsStore.open(path)
            return try {
                VaultTestOwner(ordinary, VaultSettingsStore.open(ordinary, vault, { UUID.randomUUID().toString() }))
            } catch (failure: Throwable) { ordinary.close(); throw failure }
        }
    }
}
