package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** The host drawable binding of a semantic menu icon; provider marks without an asset fall back to the note. */
@Composable
fun SharedPlayerQueueIcon(icon: PlayerQueueMenuIcon, tint: Color, modifier: Modifier) {
    Icon(painter = painterResource(icon.sharedDrawable), contentDescription = null, tint = tint, modifier = modifier)
}

private val PlayerQueueMenuIcon.sharedDrawable: DrawableResource
    get() = when (this) {
        PlayerQueueMenuIcon.RADIO -> Res.drawable.m6yp_icon_radio
        PlayerQueueMenuIcon.SYNC -> Res.drawable.m6_icon_sync
        PlayerQueueMenuIcon.SHARE -> Res.drawable.m6yp_icon_share
        PlayerQueueMenuIcon.LINK -> Res.drawable.m4_icon_link
        PlayerQueueMenuIcon.TUNE -> Res.drawable.m4hv_icon_discover_tune
        PlayerQueueMenuIcon.BEDTIME -> Res.drawable.m9_media_info_timer
        PlayerQueueMenuIcon.MOTION_PHOTOS -> Res.drawable.m4_icon_motion_photos_on
        PlayerQueueMenuIcon.PLAYLIST_ADD -> Res.drawable.m6yp_icon_playlist_add
        PlayerQueueMenuIcon.BOOKMARK -> Res.drawable.m6yp_icon_bookmark
        PlayerQueueMenuIcon.BOOKMARK_FILLED -> Res.drawable.m6yp_icon_bookmark_filled
        PlayerQueueMenuIcon.ARTIST -> Res.drawable.m4hv_icon_person
        PlayerQueueMenuIcon.ALBUM -> Res.drawable.m4_icon_album
        PlayerQueueMenuIcon.OFFLINE -> Res.drawable.m6yp_icon_offline
        PlayerQueueMenuIcon.DOWNLOAD -> Res.drawable.m6yp_icon_download
        PlayerQueueMenuIcon.PLAYLIST_PLAY -> Res.drawable.m6yp_icon_playlist_play
        PlayerQueueMenuIcon.DELETE -> Res.drawable.m4c_icon_delete
        PlayerQueueMenuIcon.QUEUE_MUSIC -> Res.drawable.m6yp_icon_queue_music
        PlayerQueueMenuIcon.GRAPHIC_EQ -> Res.drawable.m9_media_info_graphic_eq
        PlayerQueueMenuIcon.INFO -> Res.drawable.m4_icon_info
        PlayerQueueMenuIcon.EQUALIZER -> Res.drawable.m9_media_info_graphic_eq
        PlayerQueueMenuIcon.SPEED -> Res.drawable.m9_media_info_solar_speed
        PlayerQueueMenuIcon.DISCOVER_TUNE -> Res.drawable.m4hv_icon_discover_tune
        PlayerQueueMenuIcon.EDIT -> Res.drawable.m4c_icon_edit
        // No remove mark exists in the host asset set; the close mark reads as the same dismissal.
        PlayerQueueMenuIcon.REMOVE -> Res.drawable.m4c_icon_close
        PlayerQueueMenuIcon.ADD -> Res.drawable.m4c_icon_add
        PlayerQueueMenuIcon.SKIP_NEXT -> Res.drawable.m4lf_icon_skip_next
        PlayerQueueMenuIcon.CLOSE -> Res.drawable.m4c_icon_close
        PlayerQueueMenuIcon.MUSIC_NOTE -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.PROVIDER_GENERIC -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.SEARCH -> Res.drawable.m4_icon_search
        PlayerQueueMenuIcon.PROVIDER_TIDAL -> Res.drawable.phone_reference_provider_tidal
        PlayerQueueMenuIcon.PROVIDER_QOBUZ -> Res.drawable.phone_reference_provider_qobuz
        PlayerQueueMenuIcon.PROVIDER_DEEZER -> Res.drawable.phone_reference_provider_deezer
        PlayerQueueMenuIcon.PROVIDER_JIOSAAVN -> Res.drawable.phone_reference_provider_jiosaavn
        PlayerQueueMenuIcon.SPOTIFY -> Res.drawable.m9_spotify_icon
        PlayerQueueMenuIcon.PLAY -> Res.drawable.m6yp_icon_play
        PlayerQueueMenuIcon.DONE -> Res.drawable.m4hv_icon_done
        PlayerQueueMenuIcon.CAST -> Res.drawable.m6_icon_cast
        PlayerQueueMenuIcon.CAST_CONNECTED -> Res.drawable.m6_icon_cast_connected
    }
