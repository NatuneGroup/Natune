package moe.rukamori.archivetune.scrobbling

import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.models.toMediaMetadata

fun lastFmSongIdentity(title: String, artist: String?): String =
    "${title.trim().lowercase()}::${artist.orEmpty().trim().lowercase()}"

/**
 * Last.fm has no song id. Only an unambiguous canonical or scrobbled identity may recover one; the edit itself
 * is always applied by that id, never by title alone. Both identities cover plays made before and after an edit.
 */
fun editedLastFmSongIds(songs: List<Song>): Map<String, String> =
    songs.flatMap { song ->
        val effective = song.toMediaMetadata()
        listOf(effective.canonicalMetadata(), effective).flatMap { metadata ->
            buildList {
                add(lastFmSongIdentity(metadata.title, metadata.artists.joinToString { it.name }) to song.id)
                LastFmTrackMetadata.from(metadata, null)?.let {
                    add(lastFmSongIdentity(it.track, it.artist) to song.id)
                }
            }
        }
    }.groupBy({ it.first }, { it.second })
        .mapNotNull { (identity, ids) -> ids.distinct().singleOrNull()?.let { identity to it } }
        .toMap()
