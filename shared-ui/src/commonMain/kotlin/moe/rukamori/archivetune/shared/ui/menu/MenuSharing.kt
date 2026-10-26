package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.ui.resources.*

interface MenuSharing {
    suspend fun shareLink(url: String)
    suspend fun shareLocalAudio(mediaId: String, mimeType: String?)
    /** Resolve using the signed-in Telegram client, not arithmetic on TDLib's opaque message id. */
    suspend fun telegramMessageLink(mediaId: String): String?
}

class MenuShareHandler(private val sharing: MenuSharing) {
    suspend fun song(mediaId: String, isLocal: Boolean, mimeType: String? = null): Boolean {
        if (mediaId.isBlank()) return false
        // Telegram audio can also be marked local by library metadata; its identity wins.
        if (mediaId.startsWith("telegram://")) {
            val url = sharing.telegramMessageLink(mediaId)?.takeIf(::validShareLink) ?: return false
            sharing.shareLink(url)
        } else if (isLocal) sharing.shareLocalAudio(mediaId, mimeType)
        else sharing.shareLink("https://music.youtube.com/watch?v=${encodeQueryPart(mediaId)}")
        return true
    }

    /** Local playlists have a separate export action; null/blank browse ids are never a remote URL. */
    suspend fun playlist(browseId: String?, shareLink: String?): Boolean {
        val url = shareLink?.takeIf(::validShareLink)
            ?: browseId?.takeIf(String::isNotBlank)?.let { "https://music.youtube.com/playlist?list=${encodeQueryPart(it)}" }
            ?: return false
        sharing.shareLink(url)
        return true
    }

    /** Artist links exist only for a real YouTube channel identity, never for a display-name or search fallback. */
    suspend fun artist(channelId: String?): Boolean {
        val id = channelId?.takeIf { it.length > 2 && it.startsWith("UC") && it.none(Char::isWhitespace) } ?: return false
        sharing.shareLink("https://music.youtube.com/channel/${encodeQueryPart(id)}")
        return true
    }
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
