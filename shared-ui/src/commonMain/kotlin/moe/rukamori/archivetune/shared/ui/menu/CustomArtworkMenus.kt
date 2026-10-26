package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Host-owned custom artwork effects. The host opens the platform picker, saves through the custom artwork
 * repository, publishes the player canvas and shows the per-result toast. A cancelled picker is a normal
 * return, and a failed save throws so the shared menu reports it instead of a fabricated success.
 */
interface CustomArtworkMenuHost {
    /** Picks an image and saves it as the cover override of [artworkUrl]. */
    suspend fun setCover(artworkUrl: String)
    /** Picks an image or video and saves it as the canvas override of [songId]; publishes it when saved. */
    suspend fun setCanvas(songId: String)
    /** Removes the cover override of [artworkUrl]. */
    suspend fun removeCover(artworkUrl: String)
    /** Removes the canvas override of [songId] and republishes the current canvas. */
    suspend fun removeCanvas(songId: String)
}

/**
 * Custom artwork rows from the baseline artwork section. Cover rows need a cover URL, and remove rows appear
 * only when the matching override exists. [hasCover] and [hasCanvas] come from the custom artwork overrides
 * snapshot the caller observes.
 */
fun customArtworkOverflowActions(
    artworkUrl: String?,
    songId: String,
    hasCover: Boolean,
    hasCanvas: Boolean,
    host: CustomArtworkMenuHost,
): List<OverflowAction> = buildList {
    if (artworkUrl != null) add(OverflowAction("set_cover", Res.string.m6_custom_artwork_set_cover) {
        host.setCover(artworkUrl)
    })
    add(OverflowAction("set_canvas", Res.string.m6_custom_artwork_set_canvas) { host.setCanvas(songId) })
    if (artworkUrl != null && hasCover) add(OverflowAction("remove_cover", Res.string.m6_custom_artwork_remove_cover) {
        host.removeCover(artworkUrl)
    })
    if (hasCanvas) add(OverflowAction("remove_canvas", Res.string.m6_custom_artwork_remove_canvas) {
        host.removeCanvas(songId)
    })
}
