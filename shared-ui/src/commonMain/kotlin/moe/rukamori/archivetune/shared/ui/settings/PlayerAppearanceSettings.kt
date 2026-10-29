package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.player.PlayerStyleSetting
import moe.rukamori.archivetune.shared.ui.player.honors
import moe.rukamori.archivetune.shared.ui.resources.*
import androidx.compose.runtime.getValue

/** settings/appearance/player. Platform effects and the home-source editor remain real host actions. */
@Composable
fun PlayerAppearanceSettings(
    controller: SettingsController,
    blurSupported: Boolean,
    navigate: (String) -> Unit,
    chooseHomeScreens: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    SettingsPage(playerAppearanceSettingsRows(preferences, blurSupported, navigate, chooseHomeScreens),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier)
}

fun playerAppearanceSettingsRows(
    preferences: PreferenceSnapshot,
    blurSupported: Boolean,
    navigate: (String) -> Unit,
    chooseHomeScreens: () -> Unit,
): List<SettingsRow> = buildList {
    val style = preferences[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    // The same per-style answers the players render from; Metrolist has native controls.
    fun note(setting: PlayerStyleSetting) = if (style.honors(setting)) null else Res.string.m6_player_setting_not_used
    val volume = style.honors(PlayerStyleSetting.VOLUME_BAR)
    val background = preferences[PlayerBackgroundStyleKey, "DEFAULT"]
    add(SettingsRow.Action("home_screens", Res.string.m6_home_screens, chooseHomeScreens))
    add(SettingsRow.Choice("library_style", Res.string.m6_library_style, LibraryStyleKey, preferences.libraryStyle().name, listOf(
        SettingsRow.Option("DEFAULT", Res.string.m6_default), SettingsRow.Option("APPLE_MUSIC", Res.string.m6_apple_music),
    ), write = { setLibraryStyle(LibraryStyle.valueOf(it)) }))
    add(SettingsRow.Choice("player_design_style", Res.string.m6_player_design, PlayerDesignStyleKey, "V4",
        playerDesignOptions()))
    if (style == PlayerDesignStyle.METROLIST_KMP) add(SettingsRow.Choice("metrolist_presentation",
        Res.string.m6_metrolist_presentation, MetrolistPresentationKey, "MODERN", listOf(
            SettingsRow.Option("MODERN", Res.string.m6_modern), SettingsRow.Option("VINTAGE", Res.string.m6_vintage))))
    if (style == PlayerDesignStyle.TIKTOK)
        add(SettingsRow.Toggle("tiktok_main_lyrics", Res.string.m6_tiktok_strip, TikTokMainLyricsEnabledKey, false))
    if (style == PlayerDesignStyle.APPLE_MUSIC) {
        add(SettingsRow.Toggle("apple_music_animated_artwork", Res.string.m6_animated_artwork, AppleMusicAnimatedArtworkKey, true))
        add(SettingsRow.Toggle("apple_music_backdrop_wander", Res.string.m6_backdrop_wander, AppleMusicBackdropWanderKey, true))
        add(SettingsRow.Toggle("apple_music_pause_scale", Res.string.m6_pause_scale, AppleMusicPauseScaleKey, true))
        add(SettingsRow.Toggle("apple_music_title_marquee", Res.string.m6_title_marquee, AppleMusicTitleMarqueeKey, true))
        add(SettingsRow.Toggle("apple_music_seek_grow", Res.string.m6_seek_grow, AppleMusicSeekGrowKey, true))
    }
    if (style == PlayerDesignStyle.FLAMINGO)
        add(SettingsRow.Toggle("flamingo_background_effect", Res.string.m6_flamingo_effect, FlamingoBackgroundEffectKey, false))
    add(SettingsRow.Toggle("show_player_volume_bar", Res.string.m6_player_volume, ShowPlayerVolumeBarKey, true,
        enabled = volume, read = { it.showPlayerVolumeBar(style) }, write = { setPlayerVolumeBar(it) }))
    add(SettingsRow.Choice("player_background_style", Res.string.m6_player_background, PlayerBackgroundStyleKey, "DEFAULT",
        listOf(
            SettingsRow.Option("DEFAULT", Res.string.m6_default), SettingsRow.Option("GRADIENT", Res.string.m6_gradient),
            SettingsRow.Option("CUSTOM", Res.string.m6_custom), SettingsRow.Option("BLUR", Res.string.m6_blur),
            SettingsRow.Option("COLORING", Res.string.m6_coloring), SettingsRow.Option("BLUR_GRADIENT", Res.string.m6_blur_gradient),
            SettingsRow.Option("GLOW", Res.string.m6_glow), SettingsRow.Option("GLOW_ANIMATED", Res.string.m6_glow_animated),
        ), enabled = style.honors(PlayerStyleSetting.BACKGROUND), write = { selected ->
            this[PlayerBackgroundStyleKey] = selected
            if (selected == "CUSTOM") this[LyricsBackgroundStyleKey] = "CUSTOM"
            else if (this[LyricsBackgroundStyleKey] == "CUSTOM") this[LyricsBackgroundStyleKey] = "DEFAULT"
        }, description = note(PlayerStyleSetting.BACKGROUND)))
    add(SettingsRow.Choice("lyrics_background_style", Res.string.m6_lyrics_background, LyricsBackgroundStyleKey, "DEFAULT",
        listOf(SettingsRow.Option("DEFAULT", Res.string.m6_default), SettingsRow.Option("FOLLOW_THEME", Res.string.m6_follow_theme),
            SettingsRow.Option("COLORING", Res.string.m6_coloring), SettingsRow.Option("MOVING_BLUR", Res.string.m6_moving_blur)) +
            (if (background == "CUSTOM") listOf(SettingsRow.Option("CUSTOM", Res.string.m6_custom)) else emptyList()),
        enabled = background != "CUSTOM"))
    if (background == "CUSTOM")
        add(SettingsRow.Action("customized_background", Res.string.m6_custom_background, { navigate("customize_background") }))
    val blur = !preferences[DisableBlurKey, false]
    add(SettingsRow.Choice("mini_player_background_style", Res.string.m6_mini_background, MiniPlayerBackgroundStyleKey, "THEME",
        buildList {
            add(SettingsRow.Option("THEME", Res.string.m6_follow_theme))
            add(SettingsRow.Option("GRADIENT", Res.string.m6_gradient))
            add(SettingsRow.Option("GLOW", Res.string.m6_glow))
            if (blur) {
                add(SettingsRow.Option("FROSTED", Res.string.m6_frosted))
                add(SettingsRow.Option("LIQUID_GLASS", Res.string.m6_liquid_glass))
            }
        }, write = {
            this[MiniPlayerBackgroundStyleKey] =
                if (it == "LIQUID_GLASS" && (!blurSupported || !this[LiquidGlassEnabledKey, false])) "THEME" else it
        }))
    add(SettingsRow.Toggle("compact_mini_player", Res.string.m6_compact_player, CompactMiniPlayerKey, false,
        enabled = preferences.interfaceStyle() != InterfaceStyle.APPLE_MUSIC))
    add(SettingsRow.Toggle("hide_player_thumbnail", Res.string.m6_hide_thumbnail, HidePlayerThumbnailKey, false,
        description = note(PlayerStyleSetting.HIDE_THUMBNAIL), enabled = style.honors(PlayerStyleSetting.HIDE_THUMBNAIL)))
    add(SettingsRow.Decimal("thumbnail_corner_radius", Res.string.m6_thumbnail_radius, ThumbnailCornerRadiusKey, 16f, 0f..64f,
        enabled = style.honors(PlayerStyleSetting.CORNER_RADIUS), description = note(PlayerStyleSetting.CORNER_RADIUS)))
    add(SettingsRow.Toggle("crop_thumbnail_to_square", Res.string.m6_crop_square, CropThumbnailToSquareKey, false))
    add(SettingsRow.Action("aod_customize", Res.string.m6_aod_settings, { navigate("settings/appearance/aod_customized") }))
    add(SettingsRow.Choice("player_buttons_style", Res.string.m6_player_buttons, PlayerButtonsStyleKey, "DEFAULT",
        listOf(SettingsRow.Option("DEFAULT", Res.string.m6_default), SettingsRow.Option("SECONDARY", Res.string.m6_secondary)),
        enabled = style.honors(PlayerStyleSetting.BUTTONS_STYLE), description = note(PlayerStyleSetting.BUTTONS_STYLE)))
    add(SettingsRow.Choice("player_slider_style", Res.string.m6_slider_style, SliderStyleKey, "Standard",
        playbackSliderOptions(), enabled = style.honors(PlayerStyleSetting.SLIDER_STYLE),
        description = note(PlayerStyleSetting.SLIDER_STYLE)))
    add(SettingsRow.Toggle("album_canvas_enabled", Res.string.m6_album_canvas, AlbumCanvasEnabledKey, true))
}

internal fun playerDesignOptions(): List<SettingsRow.Option> = listOf(
    SettingsRow.Option("V1", Res.string.m6_player_v1), SettingsRow.Option("V2", Res.string.m6_player_v2),
    SettingsRow.Option("V3", Res.string.m6_player_v3), SettingsRow.Option("V4", Res.string.m6_player_v4),
    SettingsRow.Option("V5", Res.string.m6_player_v5), SettingsRow.Option("V6", Res.string.m6_player_v6),
    SettingsRow.Option("V7", Res.string.m6_player_v7), SettingsRow.Option("V8", Res.string.m6_player_v8),
    SettingsRow.Option("V9", Res.string.m6_player_v9), SettingsRow.Option("APPLE_MUSIC", Res.string.m6_apple_music),
    SettingsRow.Option("V10", Res.string.m6_player_v10), SettingsRow.Option("BITCHORD", Res.string.m6_bitchord),
    SettingsRow.Option("TIKTOK", Res.string.m6_tiktok), SettingsRow.Option("SIMPMUSIC", Res.string.m6_simpmusic),
    SettingsRow.Option("SPATIALFLOW", Res.string.m6_spatialflow), SettingsRow.Option("LOOPER", Res.string.m6_looper),
    SettingsRow.Option("FLAMINGO", Res.string.m6_flamingo), SettingsRow.Option("METROLIST_KMP", Res.string.m6_metrolist),
)
