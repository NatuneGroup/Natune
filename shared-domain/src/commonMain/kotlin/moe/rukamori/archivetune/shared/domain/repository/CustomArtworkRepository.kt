/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource

/**
 * The user's custom covers and canvases. A cover replaces the artwork at one URL on every surface that shows it, and a
 * canvas replaces the canvas of one song. Surfaces read [overrides] for cheap presence checks and ask [coverUri] or
 * [canvasUri] for the file to show. Edit and removal entry points belong to the settings and dialog owners; this port
 * only stores and reports.
 */
interface CustomArtworkRepository {
    val overrides: StateFlow<CustomArtworkOverrides>

    /**
     * Scans both folders once. Callers run it before the first lookup, so a surface that renders on the first frame
     * does not wait on disk. Later calls return at once.
     */
    suspend fun ensureLoaded()

    /** The file URI of the custom cover for [artworkUrl], or null when that artwork has no override. */
    suspend fun coverUri(artworkUrl: String?): String?

    /** The file URI of the custom canvas for [songId], or null when that song has no override. */
    suspend fun canvasUri(songId: String?): String?

    /** Stores [source] as the cover for [artworkUrl]. Only [CustomArtworkResult.SAVED] changes what the user sees. */
    suspend fun setCover(artworkUrl: String, source: CustomArtworkSource): CustomArtworkResult

    /** Stores [source] as the canvas for [songId]. Only [CustomArtworkResult.SAVED] changes what the user sees. */
    suspend fun setCanvas(songId: String, source: CustomArtworkSource): CustomArtworkResult

    /** Removes the cover for [artworkUrl]. False when there was none to remove. */
    suspend fun removeCover(artworkUrl: String?): Boolean

    /** Removes the canvas for [songId]. False when there was none to remove. */
    suspend fun removeCanvas(songId: String?): Boolean
}
