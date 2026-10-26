@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.platform.ios.IosKeychainCredentialStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosSettingsStore
import platform.Foundation.NSUUID

/**
 * What the root may observe. Opening and unavailable states never substitute memory, UserDefaults or a
 * plaintext file for the Keychain-backed settings owner.
 */
sealed interface IosSettingsOpenState {
    data object Opening : IosSettingsOpenState

    class Ready(val settings: IosVaultSettings) : IosSettingsOpenState

    data object Unavailable : IosSettingsOpenState
}

/**
 * The iOS settings owner graph. Ordinary preferences persist in device-only Keychain items through
 * [IosSettingsStore]. M6's [VaultSettingsStore] moves credential values into scoped Keychain items and
 * leaves only opaque UUID references in the ordinary store. [vault] is the one logical view for consumers.
 */
class IosVaultSettings private constructor(
    private val ordinary: IosSettingsStore,
    val vault: VaultSettingsStore,
) {
    /** The adapter closes first, so no consumer write can reach the ordinary store after it is released. */
    suspend fun close() = withContext(NonCancellable) {
        vault.close()
        ordinary.close()
    }

    companion object {
        /**
         * Call from a host worker, never from Swift or Compose startup. Failure releases the ordinary store's
         * single owner and surfaces as the sanitized [IosSettingsUnavailable].
         */
        suspend fun open(): IosVaultSettings {
            // The ordinary open releases its own owner on failure; only the sanitized state crosses out.
            val ordinary = try {
                IosSettingsStore.open(::requireCredentialFreePersistence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw IosSettingsUnavailable()
            }
            return try {
                IosVaultSettings(
                    ordinary,
                    VaultSettingsStore.open(
                        ordinary = ordinary,
                        vault = IosKeychainCredentialStore(),
                        newReference = { NSUUID().UUIDString },
                    ),
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { ordinary.close() }
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable) { ordinary.close() }
                throw IosSettingsUnavailable()
            }
        }
    }
}

/** Sanitized: Keychain status codes and native messages can name credential material or file paths. */
class IosSettingsUnavailable : IllegalStateException("iOS secure settings storage is unavailable")
