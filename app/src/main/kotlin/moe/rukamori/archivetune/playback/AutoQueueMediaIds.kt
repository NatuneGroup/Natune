package moe.rukamori.archivetune.playback

/**
 * Media-id scheme for the current play queue in the automotive browse tree:
 * `queue/<position>/<songMediaId>`. The pair is stable enough for the car — a tap re-verifies the
 * position against the live queue and rejects the tap when that position no longer holds the song.
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
     * Maps a tapped [entry] back to a live queue position only when the stamped position still
     * holds the same song. The id alone cannot prove which occurrence of a duplicate was tapped,
     * so a moved entry is rejected rather than remapped; the car refreshes the queue folder.
     */
    fun resolvePosition(
        entry: QueueEntry,
        queueMediaIds: List<String>,
    ): Int? = entry.position.takeIf { it in queueMediaIds.indices && queueMediaIds[it] == entry.songMediaId }
}
