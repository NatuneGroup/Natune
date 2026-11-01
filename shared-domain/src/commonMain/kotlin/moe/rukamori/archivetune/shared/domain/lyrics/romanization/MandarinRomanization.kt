package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * Offline Mandarin romanization over the bundled [MandarinPinyinData] table.
 *
 * A line is scanned left to right: at each covered Han character the phrase
 * override map is probed longest-first (up to [MandarinPinyinData.MAX_PHRASE_LENGTH]
 * characters) so polyphonic words keep their contextual reading, and a miss falls
 * back to the character's first dictionary reading. Uncovered Han characters pass
 * through untouched, so [romanize] returning the source means the table had
 * nothing to offer and the caller may try ICU instead. Tone syllables keep the
 * pinyin-data spacing (`yīn yuè`, with `er` retained for erhua), which matches how
 * the romanized row's fixed-joiner post-processing already expects syllable text.
 */
object MandarinRomanization {
    private val phrases: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>(8192)
        val raw = MandarinPinyinData.phrases
        var index = 0
        while (index < raw.length) {
            val end = raw.indexOf('\n', index).let { if (it < 0) raw.length else it }
            val row = raw.substring(index, end)
            val tab = row.indexOf('\t')
            if (tab > 0) map[row.substring(0, tab)] = row.substring(tab + 1)
            index = end + 1
        }
        map
    }

    /** The first dictionary reading of a covered Han character, or null when the table does not cover it. */
    internal fun syllableFor(char: Char): String? {
        val slot = char.code - MandarinPinyinData.BASE_CODEPOINT
        if (slot < 0 || slot >= MandarinPinyinData.cells.length) return null
        val index = MandarinPinyinData.cells[slot].code
        if (index == 0xFFFF) return null
        return MandarinPinyinData.syllables[index]
    }

    /** True when at least one character has a table reading, so [romanize] can change the line. */
    fun covers(text: String): Boolean = text.any { syllableFor(it) != null }

    /**
     * Romanizes the Han characters the table covers and leaves every other script untouched.
     * Returns null when the table cannot change [text], letting the caller fall back to ICU.
     */
    fun romanize(text: String): String? {
        if (!covers(text)) return null
        val out = StringBuilder(text.length + text.length / 2)
        var index = 0
        var lastWasSyllable = false
        while (index < text.length) {
            val char = text[index]
            if (syllableFor(char) == null) {
                if (lastWasSyllable && !char.isWhitespace()) out.append(' ')
                out.append(char)
                lastWasSyllable = false
                index++
                continue
            }
            // A romanized syllable never touches the character before it; the boundary space
            // mirrors what the ICU transliterator emits at a script change.
            if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            val phrase = phraseAt(text, index)
            if (phrase != null) {
                out.append(phrase.second)
                index += phrase.first
            } else {
                out.append(syllableFor(char))
                index++
            }
            lastWasSyllable = true
            if (index < text.length && syllableFor(text[index]) != null) out.append(' ')
        }
        val romanized = out.toString().trim()
        return romanized.takeIf { it.isNotEmpty() && it != text.trim() }
    }

    /** Longest phrase override anchored at [index], as (length, reading); null when none matches. */
    private fun phraseAt(text: String, index: Int): Pair<Int, String>? {
        val remaining = text.length - index
        var length = minOf(MandarinPinyinData.MAX_PHRASE_LENGTH, remaining)
        while (length >= 2) {
            val reading = phrases[text.substring(index, index + length)]
            if (reading != null) return length to reading
            length--
        }
        return null
    }
}
