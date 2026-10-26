package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Script lookup for common code. Replaces `Character.UnicodeScript.of`, which the Android lyric code
 * used, with the Unicode 15.0.0 Scripts.txt table generated into [UNICODE_SCRIPT_RANGE_TABLE].
 */
object UnicodeScripts {
    private val ranges: UnicodeRangeTable by lazy { UnicodeRangeTable.parse(UNICODE_SCRIPT_RANGE_TABLE) }
    private val scripts: Array<UnicodeScript> by lazy { UnicodeScript.entries.toTypedArray() }

    /**
     * The script of [codePoint]. Unassigned code points and surrogates are [UnicodeScript.UNKNOWN], as
     * on the JDK. Values outside 0..U+10FFFF are also UNKNOWN; the JDK throws there, but lyric text
     * never reaches this path with them and a lookup should not crash the lyric pipeline.
     */
    fun scriptOf(codePoint: Int): UnicodeScript {
        if (codePoint < 0 || codePoint > UNICODE_MAX_CODE_POINT) return UnicodeScript.UNKNOWN
        val index = ranges.indexOf(codePoint)
        return if (index < 0) UnicodeScript.UNKNOWN else scripts[ranges.valueAtIndex(index)]
    }
}
