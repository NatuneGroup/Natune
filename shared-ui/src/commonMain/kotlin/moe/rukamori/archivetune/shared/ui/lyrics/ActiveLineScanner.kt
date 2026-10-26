/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * Finds every row whose span covers a position, including rows that overlap in time, such as duet and
 * backing-vocal lines. Spans are sorted once by start. A query starts at the last row that has begun and
 * walks back only while an earlier row can still cover the position, using the prefix maximum of end
 * times to stop early.
 *
 * The returned list keeps its identity while the active set is unchanged, so a frame that stays inside
 * one line allocates nothing. Results are ascending row indices, which is display order.
 */
class ActiveLineScanner(starts: LongArray, ends: LongArray) {
    private val count = starts.size
    private val order: IntArray
    private val sortedStarts: LongArray
    private val sortedEnds: LongArray
    private val prefixMaxEnd: LongArray
    private val scratch = IntArray(count)
    private var hint = -1
    private var current: List<Int> = emptyList()

    init {
        require(starts.size == ends.size) { "starts and ends must have the same size" }
        order = (0 until count).sortedWith(compareBy<Int> { starts[it] }.thenBy { it }).toIntArray()
        sortedStarts = LongArray(count) { starts[order[it]] }
        sortedEnds = LongArray(count) { ends[order[it]] }
        prefixMaxEnd = LongArray(count)
        var running = Long.MIN_VALUE
        for (i in 0 until count) {
            running = maxOf(running, sortedEnds[i])
            prefixMaxEnd[i] = running
        }
    }

    /** Row indices whose span contains [positionMs], in display order. Identical instance while unchanged. */
    fun activeAt(positionMs: Long): List<Int> {
        var found = 0
        var index = lastStartAtOrBefore(positionMs)
        while (index >= 0 && prefixMaxEnd[index] > positionMs) {
            if (sortedEnds[index] > positionMs) scratch[found++] = order[index]
            index--
        }
        sortAscending(found)
        if (sameAsCurrent(found)) return current
        current = List(found) { scratch[it] }
        return current
    }

    /** The active row that began most recently, or -1 when no row covers [positionMs]. */
    fun primaryAt(positionMs: Long): Int = activeAt(positionMs).lastOrNull() ?: -1

    private fun lastStartAtOrBefore(positionMs: Long): Int {
        if (count == 0) return -1
        val h = hint
        val found = when {
            h in 0 until count && sortedStarts[h] <= positionMs &&
                (h + 1 == count || sortedStarts[h + 1] > positionMs) -> h
            h + 1 in 0 until count && sortedStarts[h + 1] <= positionMs &&
                (h + 2 == count || sortedStarts[h + 2] > positionMs) -> h + 1
            else -> binarySearchLastStart(positionMs)
        }
        hint = found
        return found
    }

    private fun binarySearchLastStart(positionMs: Long): Int {
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedStarts[mid] <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    private fun sortAscending(length: Int) {
        for (i in 1 until length) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = value
        }
    }

    private fun sameAsCurrent(length: Int): Boolean {
        if (current.size != length) return false
        for (i in 0 until length) {
            if (current[i] != scratch[i]) return false
        }
        return true
    }
}
