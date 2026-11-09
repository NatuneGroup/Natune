package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLyricsSettingsReference = PhoneSettingsReference(Res.string.desktop_shell_lyrics_pane, listOf(
    PhoneSettingReference("group:additional_downloads", Res.string.m6_settings_home_language_packs, null, PhoneSettingKind.HEADER, setOf("additional_downloads"), null, setOf(Res.string.m6_settings_home_language_packs, Res.string.m6_language_packs), false),
    PhoneSettingReference("additional_downloads", Res.string.m6_settings_home_language_packs, Res.drawable.phone_reference_translate, PhoneSettingKind.ACTION, setOf("additional_downloads"), null, setOf(Res.string.m6_settings_home_language_packs, Res.string.m6_language_packs), false),
    PhoneSettingReference("group:providers", Res.string.m6_providers, null, PhoneSettingKind.HEADER, setOf("providers"), null, setOf(Res.string.m6_providers), false),
    PhoneSettingReference("providers", Res.string.m6_providers, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("providers"), null, setOf(Res.string.m6_providers), false),
    PhoneSettingReference("group:romanization", Res.string.phone_reference_romanization, null, PhoneSettingKind.HEADER, setOf("romanization"), null, setOf(), false),
    PhoneSettingReference("romanization", Res.string.phone_reference_romanization, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("romanization"), null, setOf(), false),
    PhoneSettingReference("group:display", Res.string.phone_reference_display, null, PhoneSettingKind.HEADER, setOf("display"), null, setOf(), false),
    PhoneSettingReference("lyrics_mode", Res.string.m6_lyrics_mode, Res.drawable.phone_reference_lyrics, PhoneSettingKind.VALUE, setOf("lyrics_mode"), "lyricsMode", setOf(Res.string.m6_lyrics_mode), false),
    PhoneSettingReference("lyrics_animation_style", Res.string.phone_reference_lyrics_animation_style, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION, setOf("lyrics_animation_style"), null, setOf(), false),
    PhoneSettingReference("lyrics_click", Res.string.phone_reference_lyrics_click_change, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_click_change"), "lyricsClick", setOf(), false),
    PhoneSettingReference("lyrics_scroll", Res.string.m6_lyrics_scroll, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_auto_scroll"), "lyricsScrollKey", setOf(Res.string.m6_lyrics_scroll), false),
    PhoneSettingReference("lyrics_line_blur", Res.string.phone_reference_lyrics_line_blur, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("lyrics_line_blur"), "lyricsLineBlur", setOf(), false),
    PhoneSettingReference("lyrics_text_size", Res.string.phone_reference_lyrics_text_size, Res.drawable.phone_reference_text_fields, PhoneSettingKind.ACTION, setOf("lyrics_text_size"), null, setOf(), false),
    PhoneSettingReference("lyrics_line_spacing", Res.string.phone_reference_lyrics_line_spacing, Res.drawable.phone_reference_text_fields, PhoneSettingKind.ACTION, setOf("lyrics_line_spacing"), null, setOf(), false),
    PhoneSettingReference("group:queue", Res.string.desktop_shell_queue_pane, null, PhoneSettingKind.HEADER, setOf("queue"), null, setOf(Res.string.desktop_shell_queue_pane, Res.string.shortcut_action_queue, Res.string.m5_queue, Res.string.m6_settings_desktop_queue, Res.string.queue_title), false),
    PhoneSettingReference("preload_queue_lyrics", Res.string.m6_lyrics_preload, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("preload_queue_lyrics"), null, setOf(Res.string.m6_lyrics_preload), false),
))
