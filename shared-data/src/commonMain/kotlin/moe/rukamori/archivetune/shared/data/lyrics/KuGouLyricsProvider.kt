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
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.min

/**
 * Port of the Android KuGou wrapper and `KuGou` client, modified from ViMusic.
 *
 * The Android client carried no request signing, so none is reproduced here. The download
 * body is base64-encoded LRC, decoded as UTF-8 and then normalised.
 */
@OptIn(ExperimentalEncodingApi::class)
class KuGouLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "Kugou"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            val keyword = generateKeyword(title, artist)
            val candidate =
                getLyricsCandidate(keyword, durationSec)
                    ?: throw IllegalStateException("No lyrics candidate")
            downloadLyrics(candidate.id, candidate.accessCode)
        }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
        callback: (String) -> Unit,
    ) {
        val keyword = generateKeyword(title, artist)
        searchSongs(keyword).data.info.forEach { song ->
            if (durationSec == -1 || abs(song.duration - durationSec) <= DURATION_TOLERANCE) {
                searchLyricsByHash(song.hash).candidates.firstOrNull()?.let { candidate ->
                    callback(downloadLyrics(candidate.id, candidate.accessCode))
                }
            }
        }
        searchLyricsByKeyword(keyword, durationSec).candidates.forEach { candidate ->
            callback(downloadLyrics(candidate.id, candidate.accessCode))
        }
    }

    private suspend fun getLyricsCandidate(
        keyword: Keyword,
        duration: Int,
    ): SearchLyricsResponse.Candidate? {
        searchSongs(keyword).data.info.forEach { song ->
            // A duration of -1 means the caller does not know the length, so every song qualifies.
            if (duration == -1 || abs(song.duration - duration) <= DURATION_TOLERANCE) {
                val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return searchLyricsByKeyword(keyword, duration).candidates.firstOrNull()
    }

    private suspend fun searchSongs(keyword: Keyword): SearchSongResponse =
        LyricsHttp.getJson(client, SONG_SEARCH_URL, defaultUserAgent = false) {
            parameter("version", 9108)
            parameter("plat", 0)
            parameter("pagesize", PAGE_SIZE)
            parameter("showtype", 0)
            url.encodedParameters.append("keyword", keyword.encodedQuery())
        }

    private suspend fun searchLyricsByKeyword(
        keyword: Keyword,
        duration: Int,
    ): SearchLyricsResponse =
        LyricsHttp.getJson(client, LYRICS_SEARCH_URL, defaultUserAgent = false) {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            // Duration is omitted entirely when unknown rather than sent as a sentinel value.
            parameter("duration", duration.takeIf { it != -1 }?.times(1000))
            url.encodedParameters.append("keyword", keyword.encodedQuery())
        }

    private suspend fun searchLyricsByHash(hash: String): SearchLyricsResponse =
        LyricsHttp.getJson(client, LYRICS_SEARCH_URL, defaultUserAgent = false) {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("hash", hash)
        }

    private suspend fun downloadLyrics(
        id: Long,
        accessCode: String,
    ): String {
        val content =
            LyricsHttp
                .getJson<DownloadLyricsResponse>(client, LYRICS_DOWNLOAD_URL, defaultUserAgent = false) {
                    parameter("fmt", "lrc")
                    parameter("charset", "utf8")
                    parameter("client", "pc")
                    parameter("ver", 1)
                    parameter("id", id)
                    parameter("accesskey", accessCode)
                }.content
        return Base64.Default.decode(content).decodeToString().normalize()
    }

    private fun Keyword.encodedQuery(): String = "$title - $artist".encodeURLParameter(spaceToPlus = false)

    companion object {
        internal fun generateKeyword(
            title: String,
            artist: String,
        ): Keyword = Keyword(normalizeTitle(title), normalizeArtist(artist))
    }
}

private const val SONG_SEARCH_URL = "https://mobileservice.kugou.com/api/v3/search/song"
private const val LYRICS_SEARCH_URL = "https://lyrics.kugou.com/search"
private const val LYRICS_DOWNLOAD_URL = "https://lyrics.kugou.com/download"
private const val PAGE_SIZE = 8
private const val HEAD_CUT_LIMIT = 30
private const val DURATION_TOLERANCE = 8

// Keeps only LRC timestamp lines; anything else (headers, blank lines) is discarded.
private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()

// Credit lines such as "Lyrics by: X" have a "Label:" after the timestamp.
private val BANNED_REGEX = ".+].+[:：].+".toRegex()

private fun normalizeTitle(title: String) =
    title
        .replace("\\(.*\\)".toRegex(), "")
        .replace("（.*）".toRegex(), "")
        .replace("「.*」".toRegex(), "")
        .replace("『.*』".toRegex(), "")
        .replace("<.*>".toRegex(), "")
        .replace("《.*》".toRegex(), "")
        .replace("〈.*〉".toRegex(), "")
        .replace("＜.*＞".toRegex(), "")

private fun normalizeArtist(artist: String) =
    artist
        .replace(", ", "、")
        .replace(" & ", "、")
        .replace(".", "")
        .replace("和", "、")
        .replace("\\(.*\\)".toRegex(), "")
        .replace("（.*）".toRegex(), "")

private fun String.normalize(): String =
    replace("&apos;", "'")
        .lines()
        .filter { line -> line.matches(ACCEPTED_REGEX) }
        .let { lines ->
            // Credits at the head and tail of the file are removed, but only within the cut limits.
            var headCutLine = 0
            for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                if (lines[i].matches(BANNED_REGEX)) {
                    headCutLine = i + 1
                    break
                }
            }
            val filteredLines = lines.drop(headCutLine)

            var tailCutLine = 0
            for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                    tailCutLine = i + 1
                    break
                }
            }
            filteredLines.dropLast(tailCutLine).joinToString("\n")
        }
