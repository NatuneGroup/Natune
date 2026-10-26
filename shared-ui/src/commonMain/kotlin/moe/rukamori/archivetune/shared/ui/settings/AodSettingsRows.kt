package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

/** settings/appearance/aod_customized; hidden/disabled children retain their saved values. */
fun aodSettingsRows(preferences: PreferenceSnapshot): List<SettingsRow> = buildList {
    add(SettingsRow.Toggle("aod_customize_show_thumbnail", Res.string.m6_aod_thumbnail, AodShowThumbnailKey, true))
    add(SettingsRow.Toggle("aod_customize_show_artist", Res.string.m6_aod_artist, AodShowArtistKey, true))
    add(SettingsRow.Toggle("aod_customize_show_album", Res.string.m6_aod_album, AodShowAlbumKey, false))
    add(SettingsRow.Toggle("aod_customize_show_progress", Res.string.m6_aod_progress, AodShowProgressKey, true))
    if (preferences[AodShowProgressKey, true])
        add(SettingsRow.Toggle("aod_customize_show_time_labels", Res.string.m6_aod_times, AodShowTimeLabelsKey, true))
    add(SettingsRow.Toggle("aod_customize_show_controls", Res.string.m6_aod_controls, AodShowControlsKey, true))
    add(SettingsRow.Toggle("aod_customize_show_exit_button", Res.string.m6_aod_exit, AodShowExitButtonKey, true))
    add(SettingsRow.Toggle("aod_customize_show_lyrics", Res.string.m6_aod_lyrics, AodShowLyricsKey, true))
    add(SettingsRow.Choice("aod_thumbnail", Res.string.m6_aod_shape, AodThumbnailShapeKey, "ROUNDED", listOf(
        SettingsRow.Option("ROUNDED", Res.string.m6_shape_rounded),
        SettingsRow.Option("SQUARE", Res.string.m6_shape_square),
        SettingsRow.Option("CIRCLE", Res.string.m6_shape_circle),
        SettingsRow.Option("PILL", Res.string.m6_shape_pill),
        SettingsRow.Option("ARCH", Res.string.m6_shape_arch),
        SettingsRow.Option("SLANTED", Res.string.m6_shape_slanted),
        SettingsRow.Option("DIAMOND", Res.string.m6_shape_diamond),
        SettingsRow.Option("PENTAGON", Res.string.m6_shape_pentagon),
        SettingsRow.Option("TRIANGLE", Res.string.m6_shape_triangle),
        SettingsRow.Option("HEART", Res.string.m6_shape_heart),
        SettingsRow.Option("FLOWER", Res.string.m6_shape_flower),
        SettingsRow.Option("CLOVER_4", Res.string.m6_shape_clover),
        SettingsRow.Option("COOKIE_6", Res.string.m6_shape_cookie6),
        SettingsRow.Option("COOKIE_9", Res.string.m6_shape_cookie9),
        SettingsRow.Option("SUNNY", Res.string.m6_shape_sunny),
        SettingsRow.Option("SOFT_BURST", Res.string.m6_shape_burst),
        SettingsRow.Option("GHOSTISH", Res.string.m6_shape_ghost),
        SettingsRow.Option("PIXEL_CIRCLE", Res.string.m6_shape_pixel),
    )))
    add(SettingsRow.Choice("aod_customize_background_style", Res.string.m6_aod_background, AodBackgroundStyleKey, "PURE_BLACK", listOf(
        SettingsRow.Option("PURE_BLACK", Res.string.m6_pure_black),
        SettingsRow.Option("SOFT_RADIAL", Res.string.m6_aod_radial),
        SettingsRow.Option("TONAL_EDGE", Res.string.m6_aod_edge),
        SettingsRow.Option("AMBIENT_GLOW", Res.string.m6_aod_glow),
    )))
    add(SettingsRow.Choice("aod_customize_accent_style", Res.string.m6_aod_accent, AodAccentStyleKey, "MONOCHROME", listOf(
        SettingsRow.Option("MONOCHROME", Res.string.m6_monochrome),
        SettingsRow.Option("THEME", Res.string.m6_theme),
    )))
    add(SettingsRow.Choice("aod_customize_content_position", Res.string.m6_aod_position, AodContentPositionKey, "CENTER", listOf(
        SettingsRow.Option("TOP", Res.string.m6_top),
        SettingsRow.Option("CENTER", Res.string.m6_center),
        SettingsRow.Option("BOTTOM", Res.string.m6_bottom),
    )))
    add(SettingsRow.Choice("aod_customize_text_alignment", Res.string.m6_aod_alignment, AodTextAlignmentKey, "CENTER", listOf(
        SettingsRow.Option("START", Res.string.m6_start),
        SettingsRow.Option("CENTER", Res.string.m6_center),
        SettingsRow.Option("END", Res.string.m6_end),
    )))
    add(SettingsRow.Number("aod_customize_title_max_lines", Res.string.m6_aod_title_lines, AodTitleMaxLinesKey, 1, 1..3))
    add(SettingsRow.Choice("aod_customize_slider_style", Res.string.m6_slider_style, AodSliderStyleKey, "Standard",
        playbackSliderOptions(), enabled = preferences[AodShowProgressKey, true]))
    add(SettingsRow.Decimal("aod_customize_thumbnail_size", Res.string.m6_aod_size, AodThumbnailSizeKey, 260f, 160f..340f, 35))
    add(SettingsRow.Number("aod_customize_shape_rotation", Res.string.m6_aod_rotation, AodThumbnailShapeRotationKey, 0, 0..315))
    add(SettingsRow.Decimal("aod_customize_horizontal_padding", Res.string.m6_aod_padding, AodHorizontalPaddingKey, 40f, 16f..72f, 13))
    add(SettingsRow.Decimal("aod_customize_vertical_spacing", Res.string.m6_aod_spacing, AodVerticalSpacingKey, 20f, 8f..36f, 13))
    add(SettingsRow.Decimal("aod_customize_ambient_intensity", Res.string.m6_aod_ambient, AodAmbientIntensityKey, .18f, 0f..1f, 20))
    add(SettingsRow.Toggle("aod_customize_artwork_glow", Res.string.m6_aod_artwork_glow, AodArtworkGlowKey, true))
    add(SettingsRow.Choice("aod_customize_control_style", Res.string.m6_aod_control_style, AodControlStyleKey, "FILLED", listOf(
        SettingsRow.Option("FILLED", Res.string.m6_filled),
        SettingsRow.Option("TONAL", Res.string.m6_tonal),
        SettingsRow.Option("MINIMAL", Res.string.m6_minimal),
    ), enabled = preferences[AodShowControlsKey, true]))
    add(SettingsRow.Decimal("aod_customize_control_size", Res.string.m6_aod_control_size, AodControlSizeKey, 64f, 52f..84f, 15,
        enabled = preferences[AodShowControlsKey, true]))
    add(SettingsRow.Number("aod_customize_auto_timer", Res.string.m6_aod_timer, AodAutoTimerSecondsKey, 0, 0..180))
    add(SettingsRow.Toggle("aod_customize_auto_on_screen_dim", Res.string.m6_aod_dim, AodAutoOnScreenDimKey, false))
}

internal fun playbackSliderOptions() = listOf(
    SettingsRow.Option("Standard", Res.string.m6_standard),
    SettingsRow.Option("Wavy", Res.string.m6_wavy),
    SettingsRow.Option("Thick", Res.string.m6_thick),
    SettingsRow.Option("Circular", Res.string.m6_circular),
    SettingsRow.Option("Simple", Res.string.m6_simple),
)
