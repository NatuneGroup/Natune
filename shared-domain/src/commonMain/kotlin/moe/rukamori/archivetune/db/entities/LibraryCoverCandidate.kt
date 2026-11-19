package moe.rukamori.archivetune.db.entities

/** One stored song's cover, with one of its credited artists (null when it has none). */
data class LibraryCoverCandidate(
    val title: String?,
    val thumbnailUrl: String?,
    val artistName: String?,
    /** Epoch millis the song was added to the library, null when it is not in it. */
    val inLibrary: Long?,
)
