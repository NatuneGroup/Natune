
package app.natune.db.entities

import androidx.compose.runtime.Immutable

@Immutable
data class ListeningBySlot(
    val slot: Int,
    val timeListened: Long,
)

@Immutable
data class ListeningTotals(
    val totalPlayCount: Int,
    val totalTimeListened: Long,
)

@Immutable
data class ListeningSummary(
    val totalPlayCount: Int,
    val totalTimeListened: Long,
    val uniqueSongsCount: Int,
    val uniqueArtistsCount: Int,
    val uniqueAlbumsCount: Int,
)
