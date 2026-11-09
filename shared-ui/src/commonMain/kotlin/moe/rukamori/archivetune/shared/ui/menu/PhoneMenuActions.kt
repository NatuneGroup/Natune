package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource

/** Preserve the original action identity and label, with no callable unsupported backend. */
fun OverflowAction.unavailableOnIos(): OverflowAction = OverflowAction(id, label, enabled = false,
    dismissOnSuccess = dismissOnSuccess, destructive = destructive, confirm = confirm, confirmArgs = confirmArgs,
    description = Res.string.phone_not_available_on_ios, run = {})

internal fun phoneMenuIcon(id: String): DrawableResource = when (id) {
    "play", "play_next" -> Res.drawable.m6yp_icon_playlist_play
    "add_queue" -> Res.drawable.m6yp_icon_queue_music
    "like" -> Res.drawable.m6yp_icon_favorite_border
    "share", "export_download" -> Res.drawable.m6yp_icon_share
    "copy_link", "play_from" -> Res.drawable.m4_icon_link
    "view_artist" -> Res.drawable.phone_reference_artist
    "view_album" -> Res.drawable.m4_icon_album
    "library" -> Res.drawable.m4_icon_library_outlined
    "download", "downloading", "open_downloader", "download_cover" -> Res.drawable.m6yp_icon_download
    "remove_download", "remove_cache", "remove_history", "remove_playlist" -> Res.drawable.m4_icon_delete
    "sleep_timer" -> Res.drawable.solar_sleep
    "tempo_pitch" -> Res.drawable.phone_reference_tune
    "equalizer", "acoustic_quality" -> Res.drawable.phone_reference_graphic_eq
    "start_radio" -> Res.drawable.m6yp_icon_radio
    "speed_dial" -> Res.drawable.m6yp_icon_bookmark
    "refresh_lyrics", "refetch" -> Res.drawable.m6_icon_sync
    "custom_artwork", "save_canvas" -> Res.drawable.m4_icon_image
    "edit_lyrics", "edit_song_info" -> Res.drawable.m4c_icon_edit
    "translate_lyrics" -> Res.drawable.m6_icon_translate
    "romanize_lyrics" -> Res.drawable.phone_reference_language
    "lyrics_timing" -> Res.drawable.solar_sleep
    "search_lyrics" -> Res.drawable.m4_icon_search
    "undo_lyrics" -> Res.drawable.m4_icon_replay
    "add_playlist" -> Res.drawable.m6yp_icon_playlist_add
    else -> Res.drawable.m4_icon_info
}
