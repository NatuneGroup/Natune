@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCrypto
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceKeyPair
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecRandomDefault

/**
 * P-256 key agreement through Security.framework. The public key is exchanged as the X9.62 uncompressed
 * point (65 bytes), the same representation the JVM and Android hosts use.
 */
class IosDeviceCrypto : DeviceCrypto {
    override fun randomBytes(size: Int): ByteArray {
        require(size > 0)
        val bytes = ByteArray(size)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed ($status)" }
        return bytes
    }

    override fun generateKeyPair(): DeviceKeyPair {
        val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: error("EC key attributes allocation failed")
        var sizeNumber: COpaquePointer? = null
        try {
            CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            memScoped {
                val size = alloc<IntVar>().apply { value = 256 }
                sizeNumber = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, size.ptr)
                CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, sizeNumber)
            }
            val key = SecKeyCreateRandomKey(attributes, null) ?: error("EC key generation failed")
            val publicData = SecKeyCopyExternalRepresentation(key, null)
                ?: run {
                    CFRelease(key)
                    error("EC public key export failed")
                }
            val publicKey = publicData.toByteArray()
            CFRelease(publicData)
            require(publicKey.size == 65 && publicKey[0] == 4.toByte()) { "Security returned a non-X9.62 P-256 key" }
            return IosKeyPair(key, publicKey)
        } finally {
            sizeNumber?.let { CFRelease(it) }
            CFRelease(attributes)
        }
    }

    override fun sharedSecret(keyPair: DeviceKeyPair, peerPublicKey: ByteArray): ByteArray {
        require(keyPair is IosKeyPair) { "key pair belongs to another crypto host" }
        require(peerPublicKey.size == 65 && peerPublicKey[0] == 4.toByte()) { "peer key is not an X9.62 P-256 point" }
        val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: error("peer key attributes allocation failed")
        var publicData: CFDataRef? = null
        var peerKey: SecKeyRef? = null
        try {
            CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionarySetValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
            publicData = peerPublicKey.usePinned { pinned ->
                CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), peerPublicKey.size.toLong())
            } ?: error("peer key data allocation failed")
            peerKey = SecKeyCreateWithData(publicData, attributes, null) ?: error("peer public key rejected")
            val secret = SecKeyCopyKeyExchangeResult(
                keyPair.key,
                kSecKeyAlgorithmECDHKeyExchangeStandard,
                peerKey,
                null,
                null,
            ) ?: error("ECDH key exchange failed")
            val bytes = secret.toByteArray()
            CFRelease(secret)
            return normalize(bytes, 32)
        } finally {
            peerKey?.let { CFRelease(it) }
            publicData?.let { CFRelease(it) }
            CFRelease(attributes)
        }
    }

    private fun normalize(value: ByteArray, size: Int): ByteArray {
        if (value.size == size) return value
        val out = ByteArray(size)
        if (value.size > size) value.copyOfRange(value.size - size, value.size).copyInto(out)
        else value.copyInto(out, size - value.size)
        return out
    }

    private class IosKeyPair(val key: SecKeyRef, override val publicKey: ByteArray) : DeviceKeyPair {
        override fun destroy() = CFRelease(key)
    }
}

private fun CFDataRef?.toByteArray(): ByteArray {
    val data = this ?: return ByteArray(0)
    val length = CFDataGetLength(data).toInt()
    val pointer = CFDataGetBytePtr(data) ?: return ByteArray(0)
    return pointer.readBytes(length)
}
