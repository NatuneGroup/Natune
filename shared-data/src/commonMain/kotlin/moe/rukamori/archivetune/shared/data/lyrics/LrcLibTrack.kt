package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.Serializable

/** One row of LRCLIB's `/api/search` answer. Field names are the wire names. */
@Serializable
internal data class LrcLibTrack(
    val id: Int,
    val trackName: String,
    val artistName: String,
    val duration: Double,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)
