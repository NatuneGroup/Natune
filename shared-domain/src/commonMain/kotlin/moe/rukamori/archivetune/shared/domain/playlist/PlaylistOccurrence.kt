package moe.rukamori.archivetune.shared.domain.playlist

/**
 * One row of a playlist: a song at a position. A playlist can hold the same song more than once, so the row is the
 * identity. [setVideoId] is the remote row id that a remote playlist needs to remove this exact occurrence.
 */
data class PlaylistOccurrence(
    val id: Int,
    val songId: String,
    val position: Int,
    val setVideoId: String? = null,
)
