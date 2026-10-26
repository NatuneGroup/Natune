/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsSentinels

/**
 * One lyrics lookup. [mediaId] is the host's stable track id (a YouTube id, a Telegram id or a
 * local-media id); [artist] is the already-joined artist string the providers expect.
 */
data class LyricsRequest(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int,
    val isrc: String? = null,
)

/**
 * The orchestrator's answer. When nothing usable was found [lyrics] is [LyricsSentinels.NOT_FOUND]
 * and [providerName] is empty, matching the Android `LyricsResult`.
 */
data class LyricsResult(
    val providerName: String,
    val lyrics: String,
) {
    companion object {
        val NOT_FOUND: LyricsResult = LyricsResult(providerName = "", lyrics = LyricsSentinels.NOT_FOUND)
    }
}

/**
 * Identity shared by the result caches and single-flight. The media id is authoritative when it is
 * present. A blank id falls back to normalized title and artist, so the same song still
 * deduplicates when the host has no id for it.
 */
internal val LyricsRequest.trackKey: String
    get() {
        val id = mediaId.trim()
        if (id.isNotEmpty()) return "id:$id"
        return "meta:${normalizeKeyPart(title)}\u001F${normalizeKeyPart(artist)}"
    }

private val KEY_WHITESPACE_RUN = Regex("\\s+")

private fun normalizeKeyPart(value: String): String = value.trim().lowercase().replace(KEY_WHITESPACE_RUN, " ")
