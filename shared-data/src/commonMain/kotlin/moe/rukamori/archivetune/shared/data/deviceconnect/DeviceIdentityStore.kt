package moe.rukamori.archivetune.shared.data.deviceconnect

import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.shared.domain.deviceconnect.toHex
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectDeviceIdKey
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectDeviceNameKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * The install identity. A blank id is generated once from 16 random bytes and persisted; the name defaults
 * to the host's device name and stays user-editable. No hardware identifier is read.
 */
class DeviceIdentityStore(
    private val settings: SettingsStore,
    private val platform: DevicePlatform,
    private val defaultName: String,
    private val model: String,
    private val appVersion: String,
    private val randomBytes: (Int) -> ByteArray,
) {
    suspend fun identity(): DeviceIdentity {
        val snapshot = settings.snapshots.value ?: throw IllegalStateException("settings are not loaded yet")
        var id = snapshot[DeviceConnectDeviceIdKey, ""].trim()
        var name = snapshot[DeviceConnectDeviceNameKey, ""]
        if (!DeviceConnectHosts.isValidDeviceId(id)) {
            id = randomBytes(16).toHex()
            settings.edit { this[DeviceConnectDeviceIdKey] = id }
        }
        if (name.isBlank()) {
            name = DeviceConnectHosts.normalizeDeviceName(defaultName)
            settings.edit { this[DeviceConnectDeviceNameKey] = name }
        } else {
            name = DeviceConnectHosts.normalizeDeviceName(name)
        }
        return DeviceIdentity(
            installId = id,
            name = name,
            platform = platform,
            model = model.take(DeviceConnectLimits.MAX_MODEL),
            appVersion = appVersion.take(DeviceConnectLimits.MAX_APP_VERSION),
        )
    }

    suspend fun rename(name: String): DeviceIdentity {
        val normalized = DeviceConnectHosts.normalizeDeviceName(name)
        settings.edit { this[DeviceConnectDeviceNameKey] = normalized }
        return identity()
    }
}
