/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

/** A playlist song whose item label and video id are both [id]. */
internal fun song(
    id: String,
    setVideoId: String? = null,
    originalIndex: Int? = null,
): FinitePlaylistSong<String> =
    FinitePlaylistSong(item = id, videoId = id, setVideoId = setVideoId, originalIndex = originalIndex)

internal fun page(
    vararg songs: FinitePlaylistSong<String>,
    continuation: String? = null,
    songCountText: String? = null,
): FinitePlaylistPage<String> =
    FinitePlaylistPage(
        title = "Playlist",
        songs = songs.toList(),
        continuation = continuation,
        songCountText = songCountText,
    )
