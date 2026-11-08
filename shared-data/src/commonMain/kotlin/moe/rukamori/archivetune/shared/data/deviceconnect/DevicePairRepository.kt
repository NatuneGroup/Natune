package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairGrant
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairInfo
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceWireJson
import moe.rukamori.archivetune.shared.domain.deviceconnect.hexToBytesOrNull
import moe.rukamori.archivetune.shared.domain.deviceconnect.toHex
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectPairedDevicesKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/** Persistence for pair grants. Secrets are only ever written to the platform vault. */
interface DevicePairRepository {
    suspend fun find(peerId: String): DevicePairGrant?

    suspend fun list(): List<DevicePairInfo>

    suspend fun save(grant: DevicePairGrant)

    suspend fun remove(peerId: String)
}

/**
 * The public metadata index lives in one ordinary settings key so the settings page can list devices without
 * unlocking the vault; the 32-byte secret lives under a per-peer vault entry. Neither is exported by a backup.
 */
class SecureDevicePairRepository(
    private val vault: SecureCredentialStore,
    private val settings: SettingsStore,
) : DevicePairRepository {
    override suspend fun find(peerId: String): DevicePairGrant? {
        val info = list().firstOrNull { it.peerId == peerId } ?: return null
        val raw = vault.read(vaultKey(peerId)) ?: return null
        val stored = runCatching { DeviceWireJson.json.decodeFromString<VaultGrant>(raw) }.getOrNull() ?: return null
        val secret = stored.secret.hexToBytesOrNull() ?: return null
        if (secret.size != 32) return null
        return DevicePairGrant(stored.info.copy(peerId = peerId), secret)
    }

    override suspend fun list(): List<DevicePairInfo> {
        val raw = settings.snapshots.value?.get(DeviceConnectPairedDevicesKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            DeviceWireJson.json.decodeFromString(ListSerializer(DevicePairInfo.serializer()), raw)
        }.getOrDefault(emptyList()).take(64)
    }

    override suspend fun save(grant: DevicePairGrant) {
        require(grant.info.peerId.isNotBlank() && grant.secret.size == 32)
        val value = DeviceWireJson.json.encodeToString(VaultGrant(grant.secret.toHex(), grant.info))
        vault.write(vaultKey(grant.info.peerId), value)
        val updated = (list().filterNot { it.peerId == grant.info.peerId } + grant.info).take(64)
        try {
            settings.edit {
                this[DeviceConnectPairedDevicesKey] = DeviceWireJson.json.encodeToString(
                    ListSerializer(DevicePairInfo.serializer()),
                    updated,
                )
            }
        } catch (failure: Throwable) {
            vault.remove(vaultKey(grant.info.peerId))
            throw failure
        }
    }

    override suspend fun remove(peerId: String) {
        vault.remove(vaultKey(peerId))
        val updated = list().filterNot { it.peerId == peerId }
        settings.edit {
            this[DeviceConnectPairedDevicesKey] = DeviceWireJson.json.encodeToString(
                ListSerializer(DevicePairInfo.serializer()),
                updated,
            )
        }
    }

    private fun vaultKey(peerId: String) = CredentialKey("deviceconnect", CredentialScope.PERSONAL, "pair/$peerId")

    @Serializable
    private data class VaultGrant(val secret: String, val info: DevicePairInfo)
}
