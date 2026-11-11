package moe.rukamori.archivetune.utils

import java.time.LocalDateTime

private const val LIKED_SONG_ORDER_STEP_NANOS = 1_000_000L

internal fun reconcileLikedSongTimestamps(
    songIds: List<String>,
    existingLikedDates: Map<String, LocalDateTime>,
    fallbackTimestamp: LocalDateTime,
): List<LocalDateTime> {
    if (songIds.isEmpty()) return emptyList()

    val timestamps = songIds.map { songId -> existingLikedDates[songId] }.toMutableList()
    var index = 0
    while (index < timestamps.size) {
        if (timestamps[index] != null) {
            index += 1
            continue
        }

        val runStart = index
        while (index < timestamps.size && timestamps[index] == null) {
            index += 1
        }
        val runEnd = index
        val previousTimestamp = timestamps.getOrNull(runStart - 1)
        val nextTimestamp = timestamps.getOrNull(runEnd)

        for (runIndex in runStart until runEnd) {
            timestamps[runIndex] =
                if (previousTimestamp != null) {
                    previousTimestamp.minusNanos(
                        (runIndex - runStart + 1).toLong() * LIKED_SONG_ORDER_STEP_NANOS,
                    )
                } else if (nextTimestamp != null) {
                    nextTimestamp.plusNanos(
                        (runEnd - runIndex).toLong() * LIKED_SONG_ORDER_STEP_NANOS,
                    )
                } else {
                    fallbackTimestamp.minusNanos(runIndex.toLong() * LIKED_SONG_ORDER_STEP_NANOS)
                }
        }
    }

    return timestamps.map { timestamp ->
        checkNotNull(timestamp)
    }
}
