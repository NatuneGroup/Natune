package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAodCustomizedScreenReference = PhoneSettingsReference(Res.string.m6_settings_home_aod, listOf(
    PhoneSettingReference("group:aod_customize_visibility", Res.string.phone_reference_aod_customize_visibility, null, PhoneSettingKind.HEADER, setOf("aod_customize_visibility"), null, setOf(), false),
    PhoneSettingReference("aod_customize_show_thumbnail", Res.string.phone_reference_aod_customize_show_thumbnail, Res.drawable.phone_reference_image, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_thumbnail"), "aodShowThumbnail", setOf(), false),
    PhoneSettingReference("aod_customize_show_artist", Res.string.m6_aod_artist, Res.drawable.phone_reference_artist, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_artist"), "aodShowArtist", setOf(Res.string.m6_aod_artist), false),
    PhoneSettingReference("aod_customize_show_album", Res.string.m6_aod_album, Res.drawable.phone_reference_album, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_album"), "aodShowAlbum", setOf(Res.string.m6_aod_album), false),
    PhoneSettingReference("aod_customize_show_progress", Res.string.m6_aod_progress, Res.drawable.phone_reference_sliders, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_progress"), "aodShowProgress", setOf(Res.string.m6_aod_progress), false),
    PhoneSettingReference("aod_customize_show_time_labels", Res.string.m6_aod_times, Res.drawable.phone_reference_timer, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_time_labels"), "aodShowTimeLabels", setOf(Res.string.m6_aod_times), true),
    PhoneSettingReference("aod_customize_show_controls", Res.string.phone_reference_aod_customize_show_controls, Res.drawable.phone_reference_buttons, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_controls"), "aodShowControls", setOf(), false),
    PhoneSettingReference("aod_customize_show_exit_button", Res.string.m6_aod_exit, Res.drawable.phone_reference_close, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_exit_button"), "aodShowExitButton", setOf(Res.string.m6_aod_exit), false),
    PhoneSettingReference("aod_customize_show_lyrics", Res.string.m6_aod_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("aod_customize_show_lyrics"), "aodShowLyrics", setOf(Res.string.m6_aod_lyrics), false),
    PhoneSettingReference("group:aod_customize_scale_and_spacing", Res.string.m6_aod_size, null, PhoneSettingKind.HEADER, setOf("aod_customize_scale_and_spacing"), null, setOf(Res.string.m6_aod_size), false),
    PhoneSettingReference("group:aod_customize_lyrics", Res.string.phone_reference_aod_customize_lyrics, null, PhoneSettingKind.HEADER, setOf("aod_customize_lyrics"), null, setOf(), false),
    PhoneSettingReference("aod_customize_auto_on_screen_dim", Res.string.phone_reference_aod_customize_auto_on_screen_dim, Res.drawable.phone_reference_bedtime, PhoneSettingKind.TOGGLE, setOf("aod_customize_auto_on_screen_dim"), "aodAutoOnScreenDim", setOf(), false),
))
