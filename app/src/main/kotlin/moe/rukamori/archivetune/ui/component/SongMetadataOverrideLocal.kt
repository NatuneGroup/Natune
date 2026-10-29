package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.songmetadata.EffectiveSongMetadata
import moe.rukamori.archivetune.songmetadata.resolveEffectiveSongMetadata

/**
 * Overrides keyed by song id, provided once at the app root. Rows that show online songs read edited
 * names from here instead of each opening a database subscription of their own.
 */
val LocalSongMetadataOverrides = compositionLocalOf<Map<String, SongMetadataOverrideEntity>> { emptyMap() }

@Composable
fun effectiveSongMetadata(songId: String, title: String, artistNames: List<String>): EffectiveSongMetadata =
    resolveEffectiveSongMetadata(title, artistNames, LocalSongMetadataOverrides.current[songId])
