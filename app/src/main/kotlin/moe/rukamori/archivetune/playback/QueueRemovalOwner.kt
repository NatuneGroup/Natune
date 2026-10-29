package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import moe.rukamori.archivetune.extensions.getQueueWindows

data class QueueRemoval(val ticket: Long, val count: Int)

/** Main/player-thread undo over occurrence UIDs. An old snackbar can never add songs to a replacement queue. */
internal class QueueRemovalOwner(
    private val player: () -> Player,
    private val setShuffleOrder: (IntArray) -> Unit,
) {
    private var sequence = 0L
    private var pending: Removed? = null

    fun reset() {
        sequence++
        pending = null
    }

    fun remove(uids: Set<Any>): QueueRemoval? {
        val target = player()
        val timeline = target.currentTimeline
        val entries = (0 until timeline.windowCount).map { index ->
            val window = timeline.getWindow(index, Timeline.Window())
            Entry(window.uid, window.mediaItem)
        }
        val removed = entries.map { it.uid }.toSet().intersect(uids)
        if (removed.isEmpty()) return null
        val ticket = ++sequence
        pending = Removed(ticket, target, entries, target.getQueueWindows().map { it.uid }, removed)
        for (index in entries.indices.reversed()) {
            if (entries[index].uid in removed) target.removeMediaItem(index)
        }
        return QueueRemoval(ticket, removed.size)
    }

    fun undo(ticket: Long): Boolean {
        val saved = pending?.takeIf { it.ticket == ticket } ?: return false
        pending = null
        val target = player()
        if (target !== saved.player) return false
        val aliases = mutableMapOf<Any, Any>()
        fun indexOf(uid: Any): Int {
            val actual = aliases[uid] ?: uid
            val timeline = target.currentTimeline
            return (0 until timeline.windowCount).firstOrNull {
                timeline.getWindow(it, Timeline.Window()).uid == actual
            } ?: -1
        }
        for (index in saved.entries.indices.reversed()) {
            val entry = saved.entries[index]
            if (entry.uid !in saved.removed) continue
            val next = saved.entries.drop(index + 1).firstNotNullOfOrNull { indexOf(it.uid).takeIf { i -> i >= 0 } }
            val previous = saved.entries.take(index).asReversed()
                .firstNotNullOfOrNull { indexOf(it.uid).takeIf { i -> i >= 0 } }
            val insertion = next ?: previous?.plus(1) ?: target.mediaItemCount
            target.addMediaItem(insertion.coerceIn(0, target.mediaItemCount), entry.item)
            aliases[entry.uid] = target.currentTimeline.getWindow(insertion, Timeline.Window()).uid
        }
        if (target.shuffleModeEnabled) {
            val restored = aliases.values.toSet()
            val order = target.getQueueWindows().map { it.uid }.filterNot { it in restored }.toMutableList()
            val original = saved.playOrder.map { aliases[it] ?: it }
            for (index in original.indices.reversed()) {
                val uid = original[index]
                if (uid !in restored) continue
                val next = original.drop(index + 1).firstOrNull { it in order }
                val previous = original.take(index).lastOrNull { it in order }
                val insertion = next?.let(order::indexOf) ?: previous?.let { order.indexOf(it) + 1 } ?: order.size
                order.add(insertion, uid)
            }
            setShuffleOrder(order.map { indexOf(it) }.toIntArray())
        }
        return true
    }

    private data class Entry(val uid: Any, val item: MediaItem)
    private data class Removed(
        val ticket: Long,
        val player: Player,
        val entries: List<Entry>,
        val playOrder: List<Any>,
        val removed: Set<Any>,
    )
}
