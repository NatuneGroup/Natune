@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.CoreFoundation.CFMutableStringRef
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTransform
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringTransformStripCombiningMarks
import platform.CoreFoundation.kCFStringTransformToLatin

/**
 * The iOS counterpart of the Android `Any-Latin; Latin-ASCII` transform. Core Foundation string
 * transforms run on the same ICU tables, so kCFStringTransformToLatin plus the combining-mark strip
 * gives the same Any-Latin, then ASCII, output. A failed transform returns null, which the caller
 * treats as no reading, matching the Android engine failure.
 */
class IosLyricsTransliterator : LyricsTransliterator {
    override fun toLatin(text: String): String? {
        if (text.isEmpty()) return text
        val source = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8) ?: return null
        try {
            val mutable = CFStringCreateMutableCopy(null, 0, source) ?: return null
            try {
                if (!CFStringTransform(mutable, null, kCFStringTransformToLatin, false)) return null
                // A failed diacritic strip leaves the Latin result intact, which is still a valid reading.
                CFStringTransform(mutable, null, kCFStringTransformStripCombiningMarks, false)
                return cfStringValue(mutable)
            } finally {
                CFRelease(mutable)
            }
        } finally {
            CFRelease(source)
        }
    }
}

internal fun cfStringValue(string: CFStringRef): String? {
    // UTF-8 never exceeds four bytes per UTF-16 unit, so this buffer always fits the encoding plus NUL.
    val capacity = CFStringGetLength(string) * 4 + 1
    return memScoped {
        val buffer = allocArray<ByteVar>(capacity)
        if (CFStringGetCString(string, buffer, capacity, kCFStringEncodingUTF8)) buffer.toKString() else null
    }
}
