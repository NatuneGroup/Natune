package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides
import moe.rukamori.archivetune.shared.domain.customartwork.isLocalPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

/**
 * The custom artwork snapshot a host's cover store holds, provided once at the root. Surfaces read it for the cheap
 * presence checks that decide between a stored cover and the fallback a row draws itself. Null means the host has no
 * cover store, so no override can exist and the fallback always draws.
 */
val LocalCustomArtworkOverrides = compositionLocalOf<CustomArtworkOverrides?> { null }

/**
 * The artwork of a library row that may be a local playlist: its stored custom cover when one exists, its 2x2 mosaic
 * of the first rows' artwork when none does, and the plain loader artwork for anything else. The pseudo-URL a local
 * playlist carries resolves through the same override map the loaders read, so [overrides.hasCover] here decides what
 * the loader would have drawn.
 */
@Composable
fun PlaylistArtwork(
    item: ContentItem,
    previewItems: List<ContentItem>,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
) {
    val overrides = LocalCustomArtworkOverrides.current
    val mosaic = isLocalPlaylistCoverUri(item.artworkUri) &&
        (overrides == null || !overrides.hasCover(item.artworkUri)) &&
        previewItems.isNotEmpty()
    if (mosaic) PlaylistMosaicArtwork(previewItems, artwork, modifier) else artwork(item, modifier)
}

/**
 * The local 2x2 mosaic: the first rows' artwork in reading order. A lone item fills the frame like a single cover;
 * more share it in quadrants, and missing tiles stay the row's surface color rather than repeating another song's art.
 */
@Composable
fun PlaylistMosaicArtwork(items: List<ContentItem>, artwork: ContentArtwork, modifier: Modifier = Modifier) {
    val cells = items.take(4)
    if (cells.size == 1) {
        artwork(cells.first(), modifier)
        return
    }
    Column(modifier) {
        listOf(cells.take(2), cells.drop(2)).forEach { row ->
            Row(Modifier.weight(1f).fillMaxSize()) {
                row.forEach { item -> artwork(item, Modifier.weight(1f).aspectRatio(1f).fillMaxSize()) }
                repeat(2 - row.size) {
                    Box(Modifier.weight(1f).aspectRatio(1f).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}
