/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

/**
 * A source of raw lyric text (LRC, TTML, QRC or plain). Parsing belongs to the shared domain
 * `LyricsParser`, so providers return exactly what the upstream service returned.
 *
 * Enablement is not part of the contract: the host's orchestrator decides which providers run.
 */
interface LyricsProvider {
    val name: String

    /** A failure means no usable lyrics, a transport error or a non-2xx answer; cancellation is thrown. */
    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String? = null,
    ): Result<String>

    suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String? = null,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, durationSec, isrc).onSuccess(callback)
    }
}
