@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import platform.CoreFoundation.*
import platform.Security.*

/**
 * Generic-password items, scoped by provider AND account purpose. No synchronizable items,
 * shared access group, UserDefaults mirror, backup export or plaintext fallback.
 */
class IosKeychainCredentialStore(
    private val service: String = "moe.rukamori.archivetune.credentials",
) : SecureCredentialStore {
    private val mutex = Mutex()

    init { require(service.isNotBlank() && '\u0000' !in service) }

    override suspend fun read(key: CredentialKey): String? = withContext(Dispatchers.Default) {
        mutex.withLock {
            query(key) { query ->
                CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    result.value = null
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecItemNotFound -> null
                        errSecSuccess -> {
                            val data = requireNotNull(result.value) as CFDataRef
                            try {
                                val size = CFDataGetLength(data)
                                check(size <= MAX_SECRET_BYTES) { "Keychain item exceeds credential limit" }
                                if (size == 0L) "" else CFDataGetBytePtr(data)!!.readBytes(size.toInt()).decodeToString()
                            } finally { CFRelease(data) }
                        }
                        else -> error("Keychain read failed ($status)")
                    }
                }
            }
        }
    }

    override suspend fun write(key: CredentialKey, value: String) = withContext(Dispatchers.Default) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_SECRET_BYTES)
        mutex.withLock {
            query(key) { query ->
                val data = bytes.usePinned {
                    CFDataCreate(kCFAllocatorDefault,
                        if (bytes.isEmpty()) null else it.addressOf(0).reinterpret(), bytes.size.toLong())
                } ?: error("Keychain data allocation failed")
                val update = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
                    ?: error("Keychain query allocation failed")
                try {
                    CFDictionarySetValue(update, kSecValueData, data)
                    CFDictionarySetValue(update, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    when (val status = SecItemUpdate(query, update)) {
                        errSecSuccess -> Unit
                        errSecItemNotFound -> {
                            CFDictionarySetValue(query, kSecValueData, data)
                            CFDictionarySetValue(query, kSecAttrAccessible,
                                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                            checkStatus(SecItemAdd(query, null), "write")
                        }
                        else -> error("Keychain update failed ($status)")
                    }
                } finally {
                    CFRelease(update)
                    CFRelease(data)
                    bytes.fill(0)
                }
            }
        }
    }

    override suspend fun remove(key: CredentialKey) = withContext(Dispatchers.Default) {
        mutex.withLock {
            query(key) { query ->
                val status = SecItemDelete(query)
                if (status != errSecItemNotFound) checkStatus(status, "remove")
            }
        }
    }

    private fun <T> query(key: CredentialKey, block: (CFMutableDictionaryRef) -> T): T {
        require('\u0000' !in key.provider && '\u0000' !in key.name)
        val serviceValue = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
            ?: error("Keychain service allocation failed")
        val accountValue = CFStringCreateWithCString(null, accountId(key), kCFStringEncodingUTF8)
            ?: error("Keychain account allocation failed")
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: error("Keychain query allocation failed")
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceValue)
            CFDictionarySetValue(query, kSecAttrAccount, accountValue)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(accountValue)
            CFRelease(serviceValue)
        }
    }

    private fun checkStatus(status: Int, action: String) {
        check(status == errSecSuccess) { "Keychain $action failed ($status)" }
    }

    internal companion object {
        const val MAX_SECRET_BYTES = 64 * 1024
        fun accountId(key: CredentialKey): String =
            listOf(key.provider, key.scope.name, key.name).joinToString("") { "${it.length}:$it" }
    }
}
