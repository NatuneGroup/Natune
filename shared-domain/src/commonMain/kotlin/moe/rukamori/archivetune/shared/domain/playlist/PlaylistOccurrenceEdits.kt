package moe.rukamori.archivetune.shared.domain.playlist

/** How to take back one edit. Each edit returns the undo that reverses it. */
sealed interface PlaylistEditUndo {
    /** The edit moved the row at [fromPosition] to [toPosition]. Undo moves it back. */
    data class Move(
        val fromPosition: Int,
        val toPosition: Int,
    ) : PlaylistEditUndo

    /** The edit removed [occurrence] from its position. Undo puts that same row back there. */
    data class Reinsert(
        val occurrence: PlaylistOccurrence,
    ) : PlaylistEditUndo
}

/** The rows after an edit, ordered by position, and the undo that reverses the edit. */
data class PlaylistEdit(
    val occurrences: List<PlaylistOccurrence>,
    val undo: PlaylistEditUndo,
)

/**
 * Reorder, remove and undo over a playlist's occurrences. Positions run contiguously from zero, and a move shifts the
 * rows between the two positions the way the playlist DAO's move does. Removing a row closes its gap, the way the app
 * moves a row to the end and then deletes it.
 *
 * Undo is not an Android feature: the app has no playlist undo. It is the inverse of the same edits.
 */
object PlaylistOccurrenceEdits {
    /**
     * Moves the row at [fromPosition] to [toPosition]. A move from a position that holds no row changes nothing, and a
     * target outside the list is clamped to its nearest end.
     */
    fun move(
        occurrences: List<PlaylistOccurrence>,
        fromPosition: Int,
        toPosition: Int,
    ): PlaylistEdit {
        val ordered = occurrences.sortedBy { it.position }
        if (ordered.none { it.position == fromPosition }) {
            return PlaylistEdit(ordered, PlaylistEditUndo.Move(fromPosition, fromPosition))
        }
        val target = toPosition.coerceIn(ordered.indices)
        val lower = minOf(fromPosition, target)
        val upper = maxOf(fromPosition, target)
        val moved =
            ordered.map { row ->
                when {
                    row.position !in lower..upper -> row
                    row.position < fromPosition -> row.copy(position = row.position + 1)
                    row.position > fromPosition -> row.copy(position = row.position - 1)
                    else -> row.copy(position = target)
                }
            }
        return PlaylistEdit(moved.sortedBy { it.position }, PlaylistEditUndo.Move(fromPosition, target))
    }

    /**
     * Removes the row at [position]. Returns null when no row holds that position. The removed row travels in the undo
     * with its song and its remote row id, so undo restores the same occurrence.
     */
    fun remove(
        occurrences: List<PlaylistOccurrence>,
        position: Int,
    ): PlaylistEdit? {
        val removed = occurrences.firstOrNull { it.position == position } ?: return null
        val remaining =
            occurrences
                .filterNot { it.position == position }
                .map { row -> if (row.position > position) row.copy(position = row.position - 1) else row }
        return PlaylistEdit(remaining.sortedBy { it.position }, PlaylistEditUndo.Reinsert(removed))
    }

    /** Reverses one edit. [occurrences] must be the rows that edit produced. */
    fun undo(
        occurrences: List<PlaylistOccurrence>,
        undo: PlaylistEditUndo,
    ): List<PlaylistOccurrence> =
        when (undo) {
            is PlaylistEditUndo.Move -> move(occurrences, undo.toPosition, undo.fromPosition).occurrences
            is PlaylistEditUndo.Reinsert -> {
                val at = undo.occurrence.position
                val shifted =
                    occurrences.map { row -> if (row.position >= at) row.copy(position = row.position + 1) else row }
                (shifted + undo.occurrence).sortedBy { it.position }
            }
        }
}
