package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.ui.player.MeshBackdrop
import moe.rukamori.archivetune.ui.player.rememberMeshPalette

/**
 * One feed page's mesh field. Drawn per page so a page carries its own artwork's colours while it
 * is dragged, instead of the whole feed recolouring only after a swipe settles. Palettes come from
 * the shared per-URL cache, so a page swiped back into view shows its colours immediately.
 */
@Composable
internal fun TikTokMeshBackdrop(
    imageUrl: String?,
    trackKey: Any?,
    modifier: Modifier = Modifier,
) {
    MeshBackdrop(
        palette = rememberMeshPalette(imageUrl),
        modifier = modifier,
        trackKey = trackKey,
        reduceAnimation = LocalAnimationsDisabled.current,
        // The page lays its own legibility scrim (tiktokScrim) over this layer; two stacked
        // scrims read as a grey wash.
        scrim = false,
    )
}
