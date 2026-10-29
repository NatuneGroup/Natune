package moe.rukamori.archivetune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Newline is the only separator that cannot appear inside an artist name typed into a single-line
 * field, so it is what the override stores between names.
 */
const val SONG_METADATA_ARTIST_SEPARATOR = "\n"

/**
 * What the user typed for one song. It lives beside the canonical [SongEntity] row rather than on it,
 * so a provider or library refresh rewrites canonical data and cannot erase an edit. A null column
 * means that field has no override; a song with no overridden field has no row at all.
 */
@Entity(tableName = "song_metadata_override")
data class SongMetadataOverrideEntity(
    @PrimaryKey val songId: String,
    val title: String? = null,
    val artistNames: String? = null,
    val updatedAt: Long = 0L,
) {
    /** The overriding artist names in order, or null when the artists are not overridden. */
    fun artistNameList(): List<String>? =
        artistNames
            ?.split(SONG_METADATA_ARTIST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
}
