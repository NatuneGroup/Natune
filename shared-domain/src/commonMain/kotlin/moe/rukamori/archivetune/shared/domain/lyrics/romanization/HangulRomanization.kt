package moe.rukamori.archivetune.shared.domain.lyrics.romanization

private const val HANGUL_SYLLABLE_BASE = 0xAC00
private const val HANGUL_SYLLABLE_LAST = 0xD7A3
private const val HANGUL_LEADING_BASE = 0x1100
private const val HANGUL_VOWEL_BASE = 0x1161
private const val HANGUL_TRAILING_BASE = 0x11A7
private const val HANGUL_LEADING_COUNT = 19
private const val HANGUL_VOWEL_COUNT = 21
private const val HANGUL_TRAILING_COUNT = 28

/**
 * Romanises Hangul by decomposing each syllable into initial, medial and final jamo. A syllable's final
 * is written only when the next syllable does not supply its own initial, and liaison is read from the
 * jong table, as on Android.
 */
fun romanizeKorean(text: String): String {
    val romajaBuilder = StringBuilder()
    var prevFinal: String? = null

    // The NFC step only affects the working copy. The caller keeps the displayed lyric untouched.
    val composed = composeHangulJamo(text)
    for (i in composed.indices) {
        val char = composed[i]

        if (char in '\uAC00'..'\uD7A3') {
            val syllableIndex = char.code - HANGUL_SYLLABLE_BASE
            val choIndex = syllableIndex / (HANGUL_VOWEL_COUNT * HANGUL_TRAILING_COUNT)
            val jungIndex = (syllableIndex % (HANGUL_VOWEL_COUNT * HANGUL_TRAILING_COUNT)) / HANGUL_TRAILING_COUNT
            val jongIndex = syllableIndex % HANGUL_TRAILING_COUNT

            val choChar = Char(HANGUL_LEADING_BASE + choIndex).toString()
            val jungChar = Char(HANGUL_VOWEL_BASE + jungIndex).toString()
            val jongChar = if (jongIndex == 0) null else Char(HANGUL_TRAILING_BASE + jongIndex).toString()

            if (prevFinal != null) {
                val contextPair = prevFinal + choChar
                val jong =
                    HANGUL_ROMAJA_MAP["jong"]?.get(contextPair)
                        ?: HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal)
                        ?: prevFinal
                romajaBuilder.append(jong)
            }

            val cho = HANGUL_ROMAJA_MAP["cho"]?.get(choChar) ?: choChar
            val jung = HANGUL_ROMAJA_MAP["jung"]?.get(jungChar) ?: jungChar
            romajaBuilder.append(cho).append(jung)

            prevFinal = jongChar
        } else {
            if (prevFinal != null) {
                val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
                romajaBuilder.append(jong)
                prevFinal = null
            }
            romajaBuilder.append(char)
        }
    }

    if (prevFinal != null) {
        val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
        romajaBuilder.append(jong)
    }

    return romajaBuilder.toString()
}

/**
 * The Hangul part of Unicode NFC: a leading consonant followed by a vowel becomes an LV syllable, and an
 * LV syllable followed by a trailing consonant becomes an LVT syllable. Everything else is left as it is.
 *
 * Other NFC compositions, such as Latin letters with combining marks, are not performed here. The shared
 * module has only canonical decomposition data, not composition data. Those marks pass through to the
 * output unchanged; the Hangul romanisation never produces them, so only non-Hangul text can differ from
 * Android.
 */
internal fun composeHangulJamo(text: String): String {
    if (text.none { it in '\u1100'..'\u1112' || it in '\u1161'..'\u1175' || it in '\u11A8'..'\u11C2' }) {
        return text
    }
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val current = text[i]
        val next = text.getOrNull(i + 1)
        if (current.code in HANGUL_LEADING_BASE until HANGUL_LEADING_BASE + HANGUL_LEADING_COUNT &&
            next != null && next.code in HANGUL_VOWEL_BASE until HANGUL_VOWEL_BASE + HANGUL_VOWEL_COUNT
        ) {
            var syllable =
                HANGUL_SYLLABLE_BASE +
                    ((current.code - HANGUL_LEADING_BASE) * HANGUL_VOWEL_COUNT + (next.code - HANGUL_VOWEL_BASE)) *
                    HANGUL_TRAILING_COUNT
            i += 2
            val trailing = text.getOrNull(i)
            if (trailing != null && isTrailingJamo(trailing)) {
                syllable += trailing.code - HANGUL_TRAILING_BASE
                i++
            }
            out.append(Char(syllable))
        } else if (isLvSyllable(current) && next != null && isTrailingJamo(next)) {
            out.append(Char(current.code + next.code - HANGUL_TRAILING_BASE))
            i += 2
        } else {
            out.append(current)
            i++
        }
    }
    return out.toString()
}

private fun isTrailingJamo(char: Char): Boolean = char.code in 0x11A8..0x11C2

/** An LV syllable has no trailing consonant, so its offset from the syllable base is a multiple of 28. */
private fun isLvSyllable(char: Char): Boolean =
    char.code in HANGUL_SYLLABLE_BASE..HANGUL_SYLLABLE_LAST &&
        (char.code - HANGUL_SYLLABLE_BASE) % HANGUL_TRAILING_COUNT == 0
