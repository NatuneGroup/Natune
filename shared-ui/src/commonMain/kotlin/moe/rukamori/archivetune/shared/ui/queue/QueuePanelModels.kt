package moe.rukamori.archivetune.shared.ui.queue

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

internal data class QueueTimes(val totalMs: Long?, val remainingMs: Long?)

internal fun queueTimes(rows: List<ContentOccurrence>, currentId: String?, positionMs: Long): QueueTimes {
    fun total(entries: List<ContentOccurrence>): Long? {
        var sum = 0L
        for (entry in entries) {
            val duration = entry.item.durationMs?.takeIf { it >= 0L } ?: return null
            sum = if (duration > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + duration
        }
        return sum
    }
    val current = rows.indexOfFirst { it.occurrenceId == currentId }
    val remaining = if (current < 0) null else total(rows.drop(current))?.let {
        val duration = rows[current].item.durationMs ?: 0L
        (it - positionMs.coerceIn(0L, duration)).coerceAtLeast(0L)
    }
    return QueueTimes(total(rows), remaining)
}

internal fun queueTimeString(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    val tail = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$tail"
    else "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$tail"
}
