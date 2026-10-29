package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.compositionLocalOf
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity

/**
 * Overrides keyed by song id, provided once at the app root. Rows that show online songs read edited
 * names from here instead of each opening a database subscription of their own.
 */
val LocalSongMetadataOverrides = compositionLocalOf<Map<String, SongMetadataOverrideEntity>> { emptyMap() }
