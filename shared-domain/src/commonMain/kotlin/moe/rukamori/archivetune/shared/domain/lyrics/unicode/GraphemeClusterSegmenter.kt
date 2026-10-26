package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Extended grapheme clusters per UAX #29 for Unicode 15.0.0, the user-perceived characters that the
 * Android lyric code got from `java.text.BreakIterator`. Rules GB1 to GB999 are applied in the spec's
 * order; the GB9c Indic conjunct rule belongs to Unicode 15.1 and is deliberately absent.
 */
object GraphemeClusterSegmenter {
    private val breakRanges: UnicodeRangeTable by lazy { UnicodeRangeTable.parse(GRAPHEME_CLUSTER_BREAK_RANGE_TABLE) }
    private val pictographRanges: UnicodeRangeTable by lazy { UnicodeRangeTable.parse(EXTENDED_PICTOGRAPHIC_RANGE_TABLE) }
    private val breakValues: Array<GraphemeClusterBreak> by lazy { GraphemeClusterBreak.entries.toTypedArray() }

    /** Clusters of [text], each as its own substring in order. Empty text gives no clusters. */
    fun segment(text: String): List<String> {
        val starts = clusterStarts(text)
        return List(starts.size) { index ->
            val end = if (index + 1 < starts.size) starts[index + 1] else text.length
            text.substring(starts[index], end)
        }
    }

    fun graphemeCount(text: String): Int = clusterStarts(text).size

    /**
     * UTF-16 offsets at which each cluster begins, ascending and starting at 0 for non-empty text. A
     * cluster ends where the next begins, or at `text.length` for the last one.
     */
    fun clusterStarts(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val starts = IntArray(text.length)
        var count = 0
        var index = 0
        var previous: GraphemeClusterBreak? = null
        // Consecutive Regional_Indicator code points ending at the previous one (GB12/GB13 parity).
        var regionalRun = 0
        // Previous code point is ZWJ directly after Extended_Pictographic Extend* (GB11 left side).
        var pictographJoiner = false
        // Current position is Extended_Pictographic Extend* with no joiner consumed yet.
        var pictographBase = false
        while (index < text.length) {
            val codePoint = codePointAtIndex(text, index)
            val property = propertyOf(codePoint)
            val pictograph = pictographRanges.contains(codePoint)
            val before = previous
            if (before == null || breaksBetween(before, property, pictograph, regionalRun, pictographJoiner)) {
                starts[count++] = index
            }
            regionalRun = if (property == GraphemeClusterBreak.REGIONAL_INDICATOR) regionalRun + 1 else 0
            pictographJoiner = property == GraphemeClusterBreak.ZWJ && pictographBase
            pictographBase = pictograph || (pictographBase && property == GraphemeClusterBreak.EXTEND)
            previous = property
            index += if (codePoint >= 0x10000) 2 else 1
        }
        return starts.copyOf(count)
    }

    private fun propertyOf(codePoint: Int): GraphemeClusterBreak {
        if (codePoint < 0 || codePoint > UNICODE_MAX_CODE_POINT) return GraphemeClusterBreak.OTHER
        return breakValues[breakRanges.valueAt(codePoint, 0)]
    }

    /** True when a cluster boundary falls between [before] and the code point being considered. */
    private fun breaksBetween(
        before: GraphemeClusterBreak,
        after: GraphemeClusterBreak,
        afterIsPictograph: Boolean,
        regionalRun: Int,
        pictographJoiner: Boolean,
    ): Boolean {
        return when {
            // GB3: CR LF is never split.
            before == GraphemeClusterBreak.CR && after == GraphemeClusterBreak.LF -> false
            // GB4 and GB5: controls and line breaks stand alone.
            isControl(before) || isControl(after) -> true
            // GB6 to GB8: Hangul jamo sequences compose syllables.
            before == GraphemeClusterBreak.L && isHangulFollowingLeading(after) -> false
            (before == GraphemeClusterBreak.LV || before == GraphemeClusterBreak.V) &&
                (after == GraphemeClusterBreak.V || after == GraphemeClusterBreak.T) -> false
            (before == GraphemeClusterBreak.LVT || before == GraphemeClusterBreak.T) &&
                after == GraphemeClusterBreak.T -> false
            // GB9 and GB9a: marks, joiners and spacing marks attach to what precedes them.
            after == GraphemeClusterBreak.EXTEND ||
                after == GraphemeClusterBreak.ZWJ ||
                after == GraphemeClusterBreak.SPACING_MARK -> false
            // GB9b: a prepend character attaches to what follows it.
            before == GraphemeClusterBreak.PREPEND -> false
            // GB11: emoji ZWJ sequences.
            pictographJoiner && afterIsPictograph -> false
            // GB12 and GB13: regional indicators pair up from the start of each run.
            before == GraphemeClusterBreak.REGIONAL_INDICATOR && after == GraphemeClusterBreak.REGIONAL_INDICATOR ->
                regionalRun % 2 == 0
            // GB999: everything else breaks.
            else -> true
        }
    }

    private fun isControl(property: GraphemeClusterBreak): Boolean =
        property == GraphemeClusterBreak.CONTROL ||
            property == GraphemeClusterBreak.CR ||
            property == GraphemeClusterBreak.LF

    /** The code points GB6 lets a leading jamo (L) join: L, V, LV and LVT. */
    private fun isHangulFollowingLeading(property: GraphemeClusterBreak): Boolean =
        property == GraphemeClusterBreak.L ||
            property == GraphemeClusterBreak.V ||
            property == GraphemeClusterBreak.LV ||
            property == GraphemeClusterBreak.LVT
}
