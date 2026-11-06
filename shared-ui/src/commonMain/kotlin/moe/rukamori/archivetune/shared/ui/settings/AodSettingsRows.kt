package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

/** settings/appearance/aod_customized; the stored layout and treatment values stay readable but have no rows. */
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
    add(SettingsRow.Decimal("aod_customize_thumbnail_size", Res.string.m6_aod_size, AodThumbnailSizeKey, 260f, 160f..340f, 35))
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
