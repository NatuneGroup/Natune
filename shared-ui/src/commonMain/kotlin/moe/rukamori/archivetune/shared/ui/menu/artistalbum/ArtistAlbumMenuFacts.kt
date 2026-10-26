/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu.artistalbum

import moe.rukamori.archivetune.shared.ui.menu.AlbumMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.ArtistMenuFacts

/**
 * Library album facts. A local album has no provider playlist or channel identity, so share, download and refetch
 * are omitted rather than shown disabled, matching the baseline library album menu.
 */
data class LibraryAlbumMenuFacts(
    val album: AlbumMenuFacts,
    val isLocal: Boolean,
)

/**
 * YouTube artist facts. Radio and shuffle come from separate provider endpoints, so each row is shown only when its
 * endpoint exists in the artist page.
 */
data class YouTubeArtistMenuFacts(
    val artist: ArtistMenuFacts,
    val shuffleAvailable: Boolean,
)

/**
 * Library artist facts. Play and shuffle need at least one local song; share needs a YouTube-backed artist.
 * [ArtistMenuFacts.channelId] is the YouTube channel id, which the baseline library menu shares as the artist id.
 */
data class LibraryArtistMenuFacts(
    val artist: ArtistMenuFacts,
    val songCount: Int,
    val isYouTubeArtist: Boolean,
)

/**
 * Artist share links exist only for a real YouTube channel identity: a UC prefix and no whitespace. This is the same
 * rule MenuShareHandler.artist applies, duplicated here so the row is disabled instead of failing on tap.
 */
internal fun shareableChannelId(channelId: String?): String? =
    channelId?.takeIf { it.length > 2 && it.startsWith("UC") && it.none(Char::isWhitespace) }
