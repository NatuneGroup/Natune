/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.library

import moe.rukamori.archivetune.shared.domain.repository.ContentItem

/**
 * One album of the saved library. [album] is the album's own item: its ref is the provider album ref, and its
 * artists are the credits of its saved songs. [songCount] counts every saved song of the album. [durationMs] is the
 * sum of the stored song durations, or null when no song stored one. [playTimeMs] is the all-time play time of those
 * songs, or null when the listening store is not wired, so the caller shows no play-time figure.
 */
data class LibraryAlbumRow(
    val album: ContentItem,
    val songCount: Int,
    val durationMs: Long?,
    val playTimeMs: Long?,
)

/**
 * One artist of the saved library, keyed by its provider id. [artist] is the artist's own item. [songCount] and
 * [playTimeMs] count the saved songs that credit the artist, with the same meaning as in [LibraryAlbumRow].
 */
data class LibraryArtistRow(
    val artist: ContentItem,
    val songCount: Int,
    val playTimeMs: Long?,
)
