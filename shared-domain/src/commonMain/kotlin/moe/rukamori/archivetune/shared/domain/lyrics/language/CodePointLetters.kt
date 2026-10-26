package moe.rukamori.archivetune.shared.domain.lyrics.language

/**
 * Letter test for code points above the BMP, where `Char.isLetter` cannot see. The BMP uses `Char.isLetter`
 * directly, which has the same L* categories as `Character.isLetter` on the JVM.
 */
internal object CodePointLetters {
    private class Ranges(
        val starts: IntArray,
        val ends: IntArray,
    )

    private val ranges: Ranges by lazy { parse(SUPPLEMENTARY_LETTER_RANGES) }

    fun contains(codePoint: Int): Boolean {
        val table = ranges
        var low = 0
        var high = table.starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < table.starts[mid] -> high = mid - 1
                codePoint > table.ends[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun parse(table: String): Ranges {
        val tokens = table.trim().split(Regex("\\s+"))
        val starts = IntArray(tokens.size)
        val ends = IntArray(tokens.size)
        tokens.forEachIndexed { index, token ->
            val bounds = token.split('-')
            starts[index] = bounds[0].toInt(16)
            ends[index] = bounds[1].toInt(16)
        }
        return Ranges(starts, ends)
    }
}
