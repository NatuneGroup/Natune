/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/** Largest Unicode scalar or code point value. */
internal const val UNICODE_MAX_CODE_POINT: Int = 0x10FFFF

/**
 * Visits each code point in order, the way `String.codePoints()` does on the JVM: a well-formed
 * surrogate pair is one code point, and a lone surrogate is passed through as its own value. Inline so
 * callers can `return` from the enclosing function, as the Android `codePoints().toArray()` loops did.
 */
internal inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAtIndex(this, index)
        action(codePoint)
        index += if (codePoint >= 0x10000) 2 else 1
    }
}

/** Code point starting at UTF-16 [index]; a surrogate pair decodes to one value. */
internal fun codePointAtIndex(
    text: String,
    index: Int,
): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return high.code
}
