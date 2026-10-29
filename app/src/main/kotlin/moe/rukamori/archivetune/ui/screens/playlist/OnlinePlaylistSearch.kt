package moe.rukamori.archivetune.ui.screens.playlist

import androidx.compose.ui.util.fastAny
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.songmetadata.resolveEffectiveSongMetadata

/**
 * The playlist's own search: matches the names the user sees, edited or canonical, and keeps each
 * surviving song's original position so the list keeps its playlist indices.
 */
internal fun List<SongItem>.filterByEffectiveNames(
    query: String,
    overrides: Map<String, SongMetadataOverrideEntity>,
): List<Pair<Int, SongItem>> {
    if (query.isEmpty()) {
        return mapIndexed { index, song -> index to song }
    }
    return mapIndexed { index, song -> index to song }
        .filter { (_, song) ->
            val names = resolveEffectiveSongMetadata(
                song.title, song.artists.map { it.name }, overrides[song.id],
            )
            names.title.contains(query, ignoreCase = true) ||
                names.artistNames.fastAny { it.contains(query, ignoreCase = true) }
        }
}
