package moe.rukamori.archivetune.shared.ui.menu.artistalbum

import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.CollectionMenuHost

/**
 * Owner-backed effects the album and artist menus need beyond [CollectionMenuHost]. Each callback throws on
 * failure so the shared menu reports it; no callback has a default that pretends the effect happened.
 */
interface ArtistAlbumMenuHost : CollectionMenuHost {
    /** Re-fetches a library album from the provider and rewrites its local record. Local albums never reach it. */
    suspend fun refetchAlbum(album: ContentRef)

    /** Adds the album to the home hidden set, keyed by the album's ref id as the baseline library menu does. */
    suspend fun hideAlbumFromHome(album: ContentRef)

    /** Plays a YouTube artist's shuffle endpoint, which is a different provider queue from its radio endpoint. */
    suspend fun playArtistShuffle(artist: ContentRef)
}
