package moe.rukamori.archivetune.songmetadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.SONG_METADATA_ARTIST_SEPARATOR
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Title and artist names a song is shown with. An override replaces each field as a whole, so a song
 * never shows one overridden field next to a canonical one that contradicts it.
 */
data class EffectiveSongMetadata(
    val title: String,
    val artistNames: List<String>,
)

fun resolveEffectiveSongMetadata(
    canonicalTitle: String,
    canonicalArtistNames: List<String>,
    override: SongMetadataOverrideEntity?,
): EffectiveSongMetadata =
    EffectiveSongMetadata(
        title = override?.title ?: canonicalTitle,
        artistNames = override?.artistNameList() ?: canonicalArtistNames,
    )

/**
 * The row to store for an edit, or null when nothing differs from the canonical data. A field the
 * user left as it was is stored as null, so it keeps following the provider.
 */
fun buildSongMetadataOverride(
    songId: String,
    canonicalTitle: String,
    canonicalArtistNames: List<String>,
    enteredTitle: String,
    enteredArtistNames: List<String>,
    updatedAt: Long,
): SongMetadataOverrideEntity? {
    val title = enteredTitle.trim().takeIf { it.isNotEmpty() && it != canonicalTitle }
    val artists =
        enteredArtistNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() && it != canonicalArtistNames }
    if (title == null && artists == null) return null
    return SongMetadataOverrideEntity(
        songId = songId,
        title = title,
        artistNames = artists?.joinToString(SONG_METADATA_ARTIST_SEPARATOR),
        updatedAt = updatedAt,
    )
}

/**
 * Applies an override to playable metadata. An artist that keeps its name keeps its canonical id and
 * thumbnail, so its page can still be opened; an artist that was renamed has no canonical page to open.
 */
fun MediaMetadata.withSongMetadataOverride(override: SongMetadataOverrideEntity?): MediaMetadata {
    if (override == null) return this
    val overriddenNames = override.artistNameList()
    return copy(
        title = override.title ?: title,
        artists =
            overriddenNames?.map { name ->
                val canonical = artists.firstOrNull { it.name == name }
                MediaMetadata.Artist(
                    id = canonical?.id,
                    name = name,
                    thumbnailUrl = canonical?.thumbnailUrl,
                )
            } ?: artists,
    )
}

fun MusicDatabase.observeSongMetadataOverrideMap(): Flow<Map<String, SongMetadataOverrideEntity>> =
    observeSongMetadataOverrides().map { rows -> rows.associateBy { it.songId } }

/**
 * Process-wide copy of the override table for code that creates MediaItems without a database handle,
 * such as online songs. The database stays the source of truth; MusicService keeps this copy in step.
 */
object SongMetadataOverrideCache {
    private val state = MutableStateFlow<Map<String, SongMetadataOverrideEntity>>(emptyMap())

    val overrides: StateFlow<Map<String, SongMetadataOverrideEntity>> = state.asStateFlow()

    fun get(songId: String): SongMetadataOverrideEntity? = state.value[songId]

    fun replaceAll(overridesById: Map<String, SongMetadataOverrideEntity>) {
        state.value = overridesById
    }
}
