/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.decodeFromString

/**
 * Port of the Android BetterLyrics wrapper, the `BetterLyrics` client and the Portato wrapper.
 *
 * Output decision: the Android client does not convert TTML or QRC. It returns the upstream text
 * unchanged: the body trimmed when it starts with "<", otherwise the untrimmed `ttml` (or `lyrics`)
 * field of a JSON envelope. This port returns the same text. The app's LyricsUtils then routes it
 * with `LyricsDetection.isTtml` to `LyricsParser.parseTtml`, and with `QrcParser.isQrc` to
 * `LyricsParser.parse`. Those readers already live in shared-domain, so porting the BetterLyrics
 * `TTMLParser`/`QRCParser` here would duplicate them. A bare QRC body is not a document and is
 * rejected, as on Android; QRC is accepted only inside a JSON envelope.
 *
 * [portato] reaches the QQ endpoint with its own name and failure message, as the Android Portato wrapper did.
 */
class BetterLyricsProvider private constructor(
    private val client: HttpClient,
    override val name: String,
    private val endpoints: List<String>,
    private val unavailableMessage: String,
) : LyricsProvider {
    constructor(client: HttpClient) : this(
        client = client,
        name = "BetterLyrics",
        endpoints = listOf(TTML_LYRICS_PATH, KUGOU_LYRICS_PATH),
        unavailableMessage = "Lyrics unavailable",
    )

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            require(title.isNotBlank() && artist.isNotBlank()) { "Song title and artist are required" }
            fetchLyrics(
                title = title.trim(),
                artist = artist.trim(),
                album = album?.trim().orEmpty(),
                durationSec = durationSec,
            ) ?: throw IllegalStateException(unavailableMessage)
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String? {
        for (endpoint in endpoints) {
            fetchLyricsFromEndpoint(endpoint, title, artist, album, durationSec)?.let { return it }
        }
        return null
    }

    /** Any failure, transport or decode, reads as this endpoint having no lyrics; cancellation propagates. */
    private suspend fun fetchLyricsFromEndpoint(
        endpoint: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String? =
        suspendRunCatching {
            LyricsHttp
                .getWithStatus(client, BASE_URL + endpoint, defaultUserAgent = false) {
                    parameter("s", title)
                    parameter("a", artist)
                    if (album.isNotBlank()) parameter("al", album)
                    if (durationSec > 0) parameter("d", durationSec)
                }.body
                ?.let { decodeLyrics(it) }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * An XML body is returned trimmed. Anything else must be a JSON envelope; its `ttml` text is
     * returned without trimming, as the Android decoder did.
     */
    private fun decodeLyrics(responseText: String): String {
        val trimmed = responseText.trim()
        if (trimmed.startsWith("<")) return trimmed
        return LyricsJson.decodeFromString<BetterLyricsTtmlResponse>(responseText).ttml
    }

    companion object {
        /** The Portato variant: the QQ endpoint only, with its own display name and failure message. */
        fun portato(client: HttpClient): BetterLyricsProvider =
            BetterLyricsProvider(
                client = client,
                name = "BetterLyrics Portato",
                endpoints = listOf(PORTATO_LYRICS_PATH),
                unavailableMessage = "Portato lyrics unavailable",
            )

        private const val BASE_URL = "https://lyrics-api.boidu.dev/"
        private const val TTML_LYRICS_PATH = "getLyrics"
        private const val KUGOU_LYRICS_PATH = "kugou/getLyrics"
        private const val PORTATO_LYRICS_PATH = "qq/getLyrics"
    }
}
