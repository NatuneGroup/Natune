package app.natune.shared.ui.songmetadata

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.ContentRef
import app.natune.shared.domain.songmetadata.SongMetadataOverride
import app.natune.shared.domain.songmetadata.withSongMetadataOverride

/** One host subscription covers online rows, whose provider DTOs intentionally remain canonical. */
val LocalSongMetadataOverrides = compositionLocalOf<Map<ContentRef, SongMetadataOverride>?> { null }

@Composable
fun effectiveSong(item: ContentItem): ContentItem =
    LocalSongMetadataOverrides.current?.let { item.withSongMetadataOverride(it[item.ref]) } ?: item
