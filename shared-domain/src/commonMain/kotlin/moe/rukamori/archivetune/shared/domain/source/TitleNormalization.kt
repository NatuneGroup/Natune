/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.source

/**
 * The folding that [TitleMatch] and [TrackMatching] compare titles with. [normalize] runs the same steps as the Android
 * matcher: [fold] (lowercase, canonical decomposition with combining marks dropped, letters and digits kept), then feature
 * and version words removed.
 *
 * Common code has no `java.text.Normalizer`, and regex behavior differs between targets, so the decomposition comes from
 * [CANONICAL_DECOMPOSITION_TABLE] and Hangul is decomposed arithmetically. Classes come from [Char.category] in the BMP and
 * from the generated ranges above it. Word boundaries follow the ASCII word characters the Android regexes use, so a
 * non-ASCII letter next to a feature word still separates it.
 */
internal object TitleNormalization {
    private const val BMP_LIMIT = 0x10000
    private const val HANGUL_SYLLABLE_BASE = 0xAC00
    private const val HANGUL_SYLLABLE_LAST = 0xD7A3
    private const val HANGUL_LEADING_BASE = 0x1100
    private const val HANGUL_VOWEL_BASE = 0x1161
    private const val HANGUL_TRAILING_BASE = 0x11A7
    private const val HANGUL_TRAILING_COUNT = 28
    private const val HANGUL_NUMERIC_COUNT = 21 * HANGUL_TRAILING_COUNT

    private val featuringWords = setOf("feat", "ft", "featuring")
    private val versionWords = setOf("explicit", "clean", "remaster", "remastered", "version", "audio", "official")

    private val decompositions: Map<Int, String> by lazy { parseDecompositions(CANONICAL_DECOMPOSITION_TABLE) }
    private val letterOrNumberRanges: IntArray by lazy { parseRanges(SUPPLEMENTARY_LETTER_OR_NUMBER_RANGES) }
    private val markRanges: IntArray by lazy { parseRanges(SUPPLEMENTARY_NON_SPACING_MARK_RANGES) }

    fun normalize(value: String?): String {
        val unfeatured = cutFeaturingSuffix(fold(value))
        val unmarked = replaceWholeWords(unfeatured, versionWords)
        return collapseSpaces(unmarked)
    }

    /**
     * Lowercase, canonical decomposition with combining marks dropped, letters and digits kept, and every other run of
     * characters as one space. Feature and version words stay in place; [normalize] removes them.
     */
    fun fold(value: String?): String {
        val folded = foldCanonically((value ?: "").lowercase())
        return collapseSpaces(separateNonLetterOrNumber(folded))
    }

    /** Canonical decomposition with combining marks (category Mn) removed. Code points outside the table stay as they are. */
    private fun foldCanonically(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            index += codePointWidth(codePoint)
            val replacement = decompositions[codePoint]
            when {
                codePoint in HANGUL_SYLLABLE_BASE..HANGUL_SYLLABLE_LAST -> appendHangulJamo(out, codePoint)
                replacement != null -> out.append(replacement)
                isNonSpacingMark(codePoint) -> Unit
                else -> out.appendCodePoint(codePoint)
            }
        }
        return out.toString()
    }

    /** Every run of characters that are neither letters nor numbers becomes one space. */
    private fun separateNonLetterOrNumber(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            val width = codePointWidth(codePoint)
            if (isLetterOrNumber(codePoint)) {
                out.append(text.substring(index, index + width))
            } else if (out.lastOrNull() != ' ') {
                out.append(' ')
            }
            index += width
        }
        return out.toString()
    }

    /** Removes the first whole word that is a feature marker, and everything after it. */
    private fun cutFeaturingSuffix(text: String): String {
        var start = 0
        while (start < text.length) {
            if (!isAsciiWordCharacter(text[start])) {
                start++
                continue
            }
            val end = asciiWordEnd(text, start)
            if (text.substring(start, end) in featuringWords) return text.substring(0, start)
            start = end
        }
        return text
    }

    private fun replaceWholeWords(
        text: String,
        words: Set<String>,
    ): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (!isAsciiWordCharacter(text[index])) {
                out.append(text[index])
                index++
                continue
            }
            val end = asciiWordEnd(text, index)
            val word = text.substring(index, end)
            out.append(if (word in words) " " else word)
            index = end
        }
        return out.toString()
    }

    private fun collapseSpaces(text: String): String = text.split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun appendHangulJamo(
        out: StringBuilder,
        syllable: Int,
    ) {
        val index = syllable - HANGUL_SYLLABLE_BASE
        out.append((HANGUL_LEADING_BASE + index / HANGUL_NUMERIC_COUNT).toChar())
        out.append((HANGUL_VOWEL_BASE + (index % HANGUL_NUMERIC_COUNT) / HANGUL_TRAILING_COUNT).toChar())
        val trailing = index % HANGUL_TRAILING_COUNT
        if (trailing != 0) out.append((HANGUL_TRAILING_BASE + trailing).toChar())
    }

    private fun isLetterOrNumber(codePoint: Int): Boolean =
        if (codePoint < BMP_LIMIT) {
            isLetterOrNumberCategory(codePoint.toChar().category)
        } else {
            inRanges(codePoint, letterOrNumberRanges)
        }

    private fun isNonSpacingMark(codePoint: Int): Boolean =
        if (codePoint < BMP_LIMIT) {
            codePoint.toChar().category == CharCategory.NON_SPACING_MARK
        } else {
            inRanges(codePoint, markRanges)
        }

    private fun isLetterOrNumberCategory(category: CharCategory): Boolean =
        when (category) {
            CharCategory.UPPERCASE_LETTER,
            CharCategory.LOWERCASE_LETTER,
            CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER,
            CharCategory.OTHER_LETTER,
            CharCategory.DECIMAL_DIGIT_NUMBER,
            CharCategory.LETTER_NUMBER,
            CharCategory.OTHER_NUMBER,
            -> true
            else -> false
        }

    private fun isAsciiWordCharacter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun asciiWordEnd(
        text: String,
        start: Int,
    ): Int {
        var end = start
        while (end < text.length && isAsciiWordCharacter(text[end])) end++
        return end
    }

    private fun codePointAt(
        text: String,
        index: Int,
    ): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        return high.code
    }

    private fun codePointWidth(codePoint: Int): Int = if (codePoint >= BMP_LIMIT) 2 else 1

    private fun StringBuilder.appendCodePoint(codePoint: Int) {
        if (codePoint < BMP_LIMIT) {
            append(codePoint.toChar())
        } else {
            val offset = codePoint - BMP_LIMIT
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }

    private fun parseDecompositions(table: String): Map<Int, String> {
        val entries = HashMap<Int, String>()
        for (entry in table.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }) {
            val colon = entry.indexOf(':')
            val folded = StringBuilder()
            for (code in entry.substring(colon + 1).split('+').filter { it.isNotEmpty() }) {
                folded.appendCodePoint(code.toInt(16))
            }
            entries[entry.substring(0, colon).toInt(16)] = folded.toString()
        }
        return entries
    }

    /** Parses `START-END` hexadecimal pairs into a flat array of inclusive bounds. */
    private fun parseRanges(table: String): IntArray {
        val tokens = table.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }
        val bounds = IntArray(tokens.size * 2)
        tokens.forEachIndexed { index, token ->
            val dash = token.indexOf('-')
            bounds[index * 2] = token.substring(0, dash).toInt(16)
            bounds[index * 2 + 1] = token.substring(dash + 1).toInt(16)
        }
        return bounds
    }

    /** Binary search over the sorted, disjoint inclusive ranges that [parseRanges] produced. */
    private fun inRanges(
        codePoint: Int,
        bounds: IntArray,
    ): Boolean {
        var low = 0
        var high = bounds.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < bounds[middle * 2] -> high = middle - 1
                codePoint > bounds[middle * 2 + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}
