package moe.rukamori.archivetune.songmetadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity

fun MusicDatabase.observeSongMetadataOverrideMap(): Flow<Map<String, SongMetadataOverrideEntity>> =
    observeSongMetadataOverrides().map { rows -> rows.associateBy { it.songId } }
