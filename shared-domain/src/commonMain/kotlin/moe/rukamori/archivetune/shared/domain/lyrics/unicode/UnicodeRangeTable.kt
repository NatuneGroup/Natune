package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Sorted, disjoint inclusive code point ranges decoded from a generated table string. Each token is
 * `START-END` or `START-END:VALUE` in hexadecimal (the value is decimal). Lookup is a binary search, so
 * the tables stay as compact text the way the other generated tables in this module do.
 */
internal class UnicodeRangeTable private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val values: IntArray,
) {
    /** Index of the range containing [codePoint], or -1 when no range covers it. */
    fun indexOf(codePoint: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < starts[middle] -> high = middle - 1
                codePoint > ends[middle] -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    fun contains(codePoint: Int): Boolean = indexOf(codePoint) >= 0

    /** Value of the range at [index], as returned by [indexOf]. */
    fun valueAtIndex(index: Int): Int = values[index]

    /** Value of the range containing [codePoint], or [default] when no range covers it. */
    fun valueAt(
        codePoint: Int,
        default: Int,
    ): Int {
        val index = indexOf(codePoint)
        return if (index < 0) default else values[index]
    }

    companion object {
        /**
         * Parses [table]. Entries without a `:VALUE` suffix get value 0, which is what range-only
         * tables (the bidi and emoji-property ones) never read.
         */
        fun parse(table: String): UnicodeRangeTable {
            val entries = table.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }
            val starts = IntArray(entries.size)
            val ends = IntArray(entries.size)
            val values = IntArray(entries.size)
            entries.forEachIndexed { index, entry ->
                val colon = entry.indexOf(':')
                val range = if (colon < 0) entry else entry.substring(0, colon)
                if (colon >= 0) values[index] = entry.substring(colon + 1).toInt()
                val dash = range.indexOf('-')
                if (dash < 0) {
                    starts[index] = range.toInt(16)
                    ends[index] = starts[index]
                } else {
                    starts[index] = range.substring(0, dash).toInt(16)
                    ends[index] = range.substring(dash + 1).toInt(16)
                }
            }
            return UnicodeRangeTable(starts, ends, values)
        }
    }
}
