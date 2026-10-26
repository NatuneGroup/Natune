@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared.data.provider

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmDES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSData
import platform.Foundation.create

/** The authorized host supplies retained key material. No copied bundled key or URL logging. */
class IosJioSaavnUrlDecoder(key: ByteArray) : JioSaavnUrlDecoder {
    private val key = key.copyOf().also { require(it.size == 8) }

    override fun decode(encryptedUrl: String): String {
        require(encryptedUrl.length <= 64 * 1024)
        val ciphertext = NSData.create(base64EncodedString = encryptedUrl, options = 0u)?.toByteArray()
            ?: throw ProviderProtocolFailure()
        val output = ByteArray(ciphertext.size + DES_BLOCK_SIZE)
        val moved = memScoped {
            val movedOut = alloc<ULongVar>()
            val status = key.usePinned { keyPinned ->
                ciphertext.usePinned { inPinned ->
                    output.usePinned { outPinned ->
                        CCCrypt(
                            kCCDecrypt,
                            kCCAlgorithmDES,
                            kCCOptionPKCS7Padding or kCCOptionECBMode,
                            keyPinned.addressOf(0), key.size.toULong(),
                            null,
                            inPinned.addressOf(0), ciphertext.size.toULong(),
                            outPinned.addressOf(0), output.size.toULong(),
                            movedOut.ptr,
                        )
                    }
                }
            }
            if (status != kCCSuccess) throw ProviderProtocolFailure()
            movedOut.value.toInt()
        }
        return output.copyOf(moved).decodeToString()
    }

    private companion object {
        const val DES_BLOCK_SIZE = 8
    }
}

private fun NSData.toByteArray(): ByteArray {
    val length = length.toInt()
    val out = ByteArray(length)
    bytes?.let { pointer ->
        out.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), pointer, length.toULong())
        }
    }
    return out
}
