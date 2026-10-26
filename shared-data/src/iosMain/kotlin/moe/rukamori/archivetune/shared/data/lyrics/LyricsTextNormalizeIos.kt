@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringNormalize
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringNormalizationFormD

/**
 * Canonical NFD normalization through Core Foundation, the same ICU tables the original's
 * `Normalizer.normalize(NFD)` applies. A null/unchanged result returns the input so callers
 * still compare text instead of dropping the line.
 */
internal actual fun canonicalNfd(value: String): String {
    if (value.isEmpty()) return value
    val source = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8) ?: return value
    try {
        val mutable = CFStringCreateMutableCopy(null, 0, source) ?: return value
        try {
            CFStringNormalize(mutable, kCFStringNormalizationFormD)
            val capacity = CFStringGetLength(mutable).toLong() * 4 + 1
            return memScoped {
                val buffer = allocArray<ByteVar>(capacity)
                if (CFStringGetCString(mutable, buffer, capacity, kCFStringEncodingUTF8)) {
                    buffer.toKString()
                } else {
                    value
                }
            }
        } finally {
            CFRelease(mutable)
        }
    } finally {
        CFRelease(source)
    }
}
