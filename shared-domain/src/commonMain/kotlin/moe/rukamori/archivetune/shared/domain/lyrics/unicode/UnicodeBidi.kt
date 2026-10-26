package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Bidirectional direction for lyric layout, replacing the `Character.getDirectionality` calls the Android
 * code made. Only the strong classes count: right-to-left is R, AL, RLE and RLO; left-to-right is L,
 * LRE and LRO. Weak and neutral classes (digits, quotes, spaces, marks) are skipped, so a line that opens
 * with them takes its direction from the first strong character.
 */
object UnicodeBidi {
    private val rtlRanges: UnicodeRangeTable by lazy { UnicodeRangeTable.parse(BIDI_STRONG_RTL_RANGE_TABLE) }
    private val ltrRanges: UnicodeRangeTable by lazy { UnicodeRangeTable.parse(BIDI_STRONG_LTR_RANGE_TABLE) }

    fun isStrongRightToLeft(codePoint: Int): Boolean = rtlRanges.contains(codePoint)

    fun isStrongLeftToRight(codePoint: Int): Boolean = ltrRanges.contains(codePoint)

    /**
     * Whether [text] should lay out right-to-left: decided by the first code point with a strong direction.
     * An Arabic line that opens with a quote mark or a digit is still an Arabic line, and kana or Latin
     * text with no strong right-to-left character stays left-to-right. Unassigned code points are skipped,
     * since the JDK reports them as undefined direction and Android ignored them.
     */
    fun isRtlText(text: String): Boolean {
        text.forEachCodePoint { codePoint ->
            if (isStrongRightToLeft(codePoint)) return true
            if (isStrongLeftToRight(codePoint)) return false
        }
        return false
    }
}
