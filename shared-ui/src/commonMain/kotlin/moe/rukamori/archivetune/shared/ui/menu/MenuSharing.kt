package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.ui.resources.*

interface MenuSharing {
    suspend fun shareLink(url: String)

    /** Writes the link to the host clipboard; the host shows its own copied feedback. */
    suspend fun copyLink(url: String)

    suspend fun shareLocalAudio(mediaId: String, mimeType: String?)
    /** Resolve using the signed-in Telegram client, not arithmetic on TDLib's opaque message id. */
    suspend fun telegramMessageLink(mediaId: String): String?
}

class MenuShareHandler(private val sharing: MenuSharing) {
    suspend fun song(mediaId: String, isLocal: Boolean, mimeType: String? = null): Boolean {
        if (mediaId.isBlank()) return false
        // Telegram audio can also be marked local by library metadata; its identity wins.
        if (mediaId.startsWith("telegram://") || !isLocal) {
            val url = songLink(mediaId, isLocal) ?: return false
            sharing.shareLink(url)
        } else {
            sharing.shareLocalAudio(mediaId, mimeType)
        }
        return true
    }

    /** The shareable URL of a song, or null for local files and unresolved Telegram message links. */
    suspend fun songLink(mediaId: String, isLocal: Boolean): String? {
        if (mediaId.isBlank()) return null
        if (mediaId.startsWith("telegram://")) {
            return sharing.telegramMessageLink(mediaId)?.takeIf(::validShareLink)
        }
        if (isLocal) return null
        return "https://music.youtube.com/watch?v=${encodeQueryPart(mediaId)}"
    }

    /** Copies the song's share link; local files and unresolved Telegram songs have none. */
    suspend fun copySongLink(mediaId: String, isLocal: Boolean): Boolean =
        songLink(mediaId, isLocal)?.also { sharing.copyLink(it) } != null

    /** Local playlists have a separate export action; null/blank browse ids are never a remote URL. */
    fun playlistLink(browseId: String?, shareLink: String?): String? =
        shareLink?.takeIf(::validShareLink)
            ?: browseId?.takeIf(String::isNotBlank)?.let { "https://music.youtube.com/playlist?list=${encodeQueryPart(it)}" }

    suspend fun playlist(browseId: String?, shareLink: String?): Boolean {
        val url = playlistLink(browseId, shareLink) ?: return false
        sharing.shareLink(url)
        return true
    }

    suspend fun copyPlaylistLink(browseId: String?, shareLink: String?): Boolean =
        playlistLink(browseId, shareLink)?.also { sharing.copyLink(it) } != null

    /** Artist links exist only for a real YouTube channel identity, never for a display-name or search fallback. */
    fun artistLink(channelId: String?): String? =
        channelId?.takeIf { it.length > 2 && it.startsWith("UC") && it.none(Char::isWhitespace) }
            ?.let { "https://music.youtube.com/channel/${encodeQueryPart(it)}" }

    suspend fun artist(channelId: String?): Boolean {
        val url = artistLink(channelId) ?: return false
        sharing.shareLink(url)
        return true
    }

    suspend fun copyArtistLink(channelId: String?): Boolean =
        artistLink(channelId)?.also { sharing.copyLink(it) } != null
}

/**
 * [url] with the playback position appended as YouTube's `t` parameter, the link the baseline's
 * "copy URL at current time" row wrote. A position under one second keeps the plain link.
 */
fun shareLinkAtPosition(url: String, positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1_000L
    if (seconds <= 0L) return url
    return url + (if ('?' in url) "&t=" else "?t=") + seconds
}

data class ArtistChoice<T : Any>(val name: String, val original: T)

/** A split display name is not a new artist identity. Every choice keeps the source channel/reference. */
fun <T : Any> splitArtistChoices(name: String, original: T, separators: String): List<ArtistChoice<T>> =
    (if (separators.isEmpty()) listOf(name) else name.split(*separators.toCharArray()))
        .map(String::trim).filter(String::isNotEmpty).map { ArtistChoice(it, original) }

private fun validShareLink(url: String): Boolean =
    (url.startsWith("https://") || url.startsWith("http://")) &&
        url.substringAfter("://").substringBefore('/').isNotBlank() && url.none(Char::isWhitespace)

private fun encodeQueryPart(value: String): String = buildString {
    val digits = "0123456789ABCDEF"
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-._~") append(n.toChar())
        else { append('%'); append(digits[n ushr 4]); append(digits[n and 15]) }
    }
}

/** Share row for artist/playlist/album collections whose only share target is a link. */
fun collectionShareAction(run: suspend () -> Unit): OverflowAction =
    OverflowAction("share", Res.string.m6_share, run = run)
