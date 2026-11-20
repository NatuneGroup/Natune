package app.natune.songmetadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.natune.db.MusicDatabase
import app.natune.db.entities.SongMetadataOverrideEntity

fun MusicDatabase.observeSongMetadataOverrideMap(): Flow<Map<String, SongMetadataOverrideEntity>> =
    observeSongMetadataOverrides().map { rows -> rows.associateBy { it.songId } }
