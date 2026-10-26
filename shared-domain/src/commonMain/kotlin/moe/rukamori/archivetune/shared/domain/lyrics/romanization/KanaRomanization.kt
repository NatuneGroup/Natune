package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/** Converts a Katakana string to romaji. Hiragana must be converted first with [hiraganaToKatakana]. */
fun katakanaToRomaji(
    katakana: String?,
    nextKatakana: String? = null,
): String {
    if (katakana.isNullOrEmpty()) return ""

    val romajiBuilder = StringBuilder(katakana.length)
    var i = 0
    val n = katakana.length
    while (i < n) {
        var consumed = false
        // Two-character entries (youon such as キャ) must win over their first kana.
        if (i + 1 < n) {
            val mappedTwoChar = KANA_ROMAJI_MAP[katakana.substring(i, i + 2)]
            if (mappedTwoChar != null) {
                romajiBuilder.append(mappedTwoChar)
                i += 2
                consumed = true
            }
        }

        // Sokuon (ッ) geminates the next consonant. The following kana is read from the same string
        // first; the next token's first kana is only a fallback because a sokuon at a token boundary is
        // a tokenizer artifact.
        if (!consumed && katakana[i] == 'ッ') {
            val nextCharToDouble = katakana.getOrNull(i + 1) ?: nextKatakana?.getOrNull(0)
            if (nextCharToDouble != null) {
                val nextCharRomaji =
                    KANA_ROMAJI_MAP[nextCharToDouble.toString()]
                        ?: nextCharToDouble.toString()
                // A vowel-initial kana geminates its vowel, which matches emphatic romaji conventions.
                val firstLetter = nextCharRomaji.firstOrNull()?.lowercase()?.trim()
                if (firstLetter != null && firstLetter.isNotEmpty()) {
                    romajiBuilder.append(firstLetter)
                }
            }
            i += 1
            consumed = true
        }

        // Chōonpu (ー) extends the previous vowel instead of being dropped, so カー becomes "kaa". After a
        // consonant or at the start of the string it emits nothing.
        if (!consumed && katakana[i] == 'ー') {
            val extension =
                when (romajiBuilder.lastOrNull()) {
                    'a' -> "a"
                    'i' -> "i"
                    'u' -> "u"
                    'e' -> "e"
                    'o' -> "o"
                    else -> ""
                }
            romajiBuilder.append(extension)
            i += 1
            consumed = true
        }

        if (!consumed) {
            val oneCharCandidate = katakana[i].toString()
            // Characters outside the map pass through unchanged, so Latin and punctuation survive.
            romajiBuilder.append(KANA_ROMAJI_MAP[oneCharCandidate] ?: oneCharCandidate)
            i += 1
        }
    }
    return romajiBuilder.toString().lowercase()
}

/** Maps every hiragana code point to its katakana counterpart, which sits 0x60 above it. */
internal fun hiraganaToKatakana(text: String): String {
    if (text.isEmpty()) return text
    val sb = StringBuilder(text.length)
    for (ch in text) {
        sb.append(if (ch in '\u3041'..'\u3096') Char(ch.code + 0x60) else ch)
    }
    return sb.toString()
}
