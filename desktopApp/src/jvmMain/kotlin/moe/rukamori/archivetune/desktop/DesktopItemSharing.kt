package moe.rukamori.archivetune.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLEncoder
import org.drinkless.tdlib.TdApi
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.MenuSharing

internal suspend fun shareDesktopItem(
    item: ContentItem,
    graph: DesktopSessionGraph,
    state: DesktopItemMenuState,
    sharing: MenuShareHandler,
) {
    // Telegram's link needs the signed-in client's lookup, which the shared handler owns; any other id keeps the
    // catalog link, and a local file keeps the reveal.
    if (item.ref.id.startsWith("telegram://")) {
        if (!sharing.song(item.ref.id, isLocal = false)) {
            state.unavailable("content_menu_telegram_share_unsupported")
        }
        return
    }
    if (item.ref.provider == ContentProvider.LOCAL.id) {
        val file = graph.localMedia.localPathOf(item.ref.id)?.let { File(it) }
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

/** Reveals the audio file a local or downloaded song resolves to, as the share sheet's local branch did. */
internal suspend fun revealDesktopAudio(mediaId: String, graph: DesktopSessionGraph): Boolean {
    val path = graph.localMedia.localPathOf(mediaId)?.let { File(it) }
        ?: run {
            val saved = try {
                graph.library.findSong(mediaId, null)
            } catch (_: Exception) {
                null
            }
            saved?.ref?.let { graph.downloads.completedFile(it) }?.path?.toFile()
        } ?: return false
    if (!path.isFile || !Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    return when {
        desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> {
            desktop.browseFileDirectory(path)
            true
        }
        path.parentFile?.isDirectory == true && desktop.isSupported(Desktop.Action.OPEN) -> {
            desktop.open(path.parentFile)
            true
        }
        else -> false
    }
}

private fun copyDesktopMenuText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

internal fun desktopMenuSharing(state: DesktopItemMenuState, graph: DesktopSessionGraph): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = copyDesktopMenuText(url)

    override suspend fun copyLink(url: String) = copyDesktopMenuText(url)

    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) {
        if (!revealDesktopAudio(mediaId, graph)) state.unavailable("content_menu_local_share_unavailable")
    }

    /** The channel message link of a Telegram song, fetched like the playlist share's own lookup. */
    override suspend fun telegramMessageLink(mediaId: String): String? {
        val track = TelegramTrackRef.fromMediaId(mediaId) ?: return null
        val link = graph.telegram.admittedRequest(
            "telegramMessageLink",
            TdApi.GetMessageLink(track.chatId, track.messageId, 0, 0, null, false, false),
        ) as? TdApi.MessageLink
        return link?.link?.takeIf(String::isNotBlank)
    }
}
