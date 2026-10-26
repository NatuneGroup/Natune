package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFMutableStringRef
import platform.CoreFoundation.CFStringNormalize
import platform.CoreFoundation.kCFStringNormalizationFormD
import platform.Foundation.NSMutableString

@OptIn(ExperimentalForeignApi::class)
internal actual fun canonicalNfd(value: String): String {
    val buffer = NSMutableString(string = value)
    CFStringNormalize(buffer as CFMutableStringRef, kCFStringNormalizationFormD)
    return buffer as String
}
