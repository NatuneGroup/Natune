package moe.rukamori.archivetune.playback

/**
 * Media-id scheme for the current play queue in the automotive browse tree:
 * `queue/<position>/<songMediaId>`. The pair is stable enough for the car — a tap re-verifies the
 * position against the live queue and falls back to matching the song id when the queue moved
 * between browse and tap.
 */
object AutoQueueMediaIds {
    const val ROOT_ID = "queue"

    data class QueueEntry(
        val position: Int,
        val songMediaId: String,
    )

    fun mediaIdFor(
        position: Int,
        songMediaId: String,
    ): String = "$ROOT_ID/$position/$songMediaId"

    fun parse(mediaId: String): QueueEntry? {
        if (!mediaId.startsWith("$ROOT_ID/")) return null
        val rest = mediaId.removePrefix("$ROOT_ID/")
        val position = rest.substringBefore('/').toIntOrNull() ?: return null
        if (position < 0) return null
        val songMediaId = rest.substringAfter('/', "")
        if (songMediaId.isBlank()) return null
        return QueueEntry(position, songMediaId)
    }

    /**
     * Maps a tapped [entry] back to a live queue position: the stamped position wins when it still
     * holds the same song, otherwise the first queue item with the same media id is used.
     */
    fun resolvePosition(
        entry: QueueEntry,
        queueMediaIds: List<String>,
    ): Int? {
        if (entry.position in queueMediaIds.indices && queueMediaIds[entry.position] == entry.songMediaId) {
            return entry.position
        }
        return queueMediaIds.indexOfFirst { it == entry.songMediaId }.takeIf { it >= 0 }
    }
}
