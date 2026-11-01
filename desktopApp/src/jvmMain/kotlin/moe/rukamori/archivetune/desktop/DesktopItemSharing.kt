package moe.rukamori.archivetune.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.MenuSharing

internal suspend fun shareDesktopItem(item: ContentItem, graph: DesktopSessionGraph, state: DesktopItemMenuState) {
    if (item.ref.provider == ContentProvider.LOCAL.id) {
        val file = graph.localMedia.localPathOf(item.ref.id)?.let { java.io.File(it) }
            ?: state.unavailable("content_menu_local_share_unavailable")
        if (!Desktop.isDesktopSupported()) state.unavailable("content_menu_local_share_unavailable")
        val desktop = Desktop.getDesktop()
        when {
            desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
            desktop.isSupported(Desktop.Action.OPEN) -> desktop.open(file.parentFile)
            else -> state.unavailable("content_menu_local_share_unavailable")
        }
        return
    }
    val uri = desktopItemShareUri(item.ref) ?: state.unavailable(
        if (item.ref.id.startsWith("telegram://")) "content_menu_telegram_share_unsupported" else "content_menu_share_unsupported",
    )
    copyDesktopMenuText(uri)
}

/** Catalog identity chooses the link; a Spotify id must never become a YouTube video id. */
internal fun desktopItemShareUri(ref: ContentRef): String? {
    if (ref.id.startsWith("telegram://")) return null
    val id = URLEncoder.encode(ref.id, "UTF-8").replace("+", "%20")
    return when (ref.provider) {
        ContentProvider.YOUTUBE.id -> when (ref.kind) {
            ContentKind.SONG, ContentKind.EPISODE -> "https://music.youtube.com/watch?v=$id"
            ContentKind.ARTIST -> "https://music.youtube.com/channel/$id"
            ContentKind.PLAYLIST -> "https://music.youtube.com/playlist?list=$id"
            ContentKind.ALBUM, ContentKind.PODCAST -> "https://music.youtube.com/browse/$id"
            else -> null
        }
        ContentProvider.SPOTIFY_CATALOG.id -> {
            val type = when (ref.kind) {
                ContentKind.SONG -> "track"
                ContentKind.EPISODE -> "episode"
                ContentKind.ARTIST -> "artist"
                ContentKind.PLAYLIST -> "playlist"
                ContentKind.ALBUM -> "album"
                ContentKind.PODCAST -> "show"
                else -> return null
            }
            "https://open.spotify.com/$type/$id"
        }
        else -> null
    }
}

private fun copyDesktopMenuText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

internal fun desktopMenuSharing(state: DesktopItemMenuState): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = copyDesktopMenuText(url)

    override suspend fun copyLink(url: String) = copyDesktopMenuText(url)
    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) =
        state.unavailable("content_menu_local_share_unavailable")
    override suspend fun telegramMessageLink(mediaId: String): String? = null
}
