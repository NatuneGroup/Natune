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
