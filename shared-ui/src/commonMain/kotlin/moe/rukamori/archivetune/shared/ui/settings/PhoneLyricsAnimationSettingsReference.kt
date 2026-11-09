package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLyricsAnimationSettingsReference = PhoneSettingsReference(Res.string.phone_reference_lyrics_animation_style, listOf(
    PhoneSettingReference("group:settings_lyrics_tuning", Res.string.phone_reference_settings_lyrics_tuning, null, PhoneSettingKind.HEADER, setOf("settings_lyrics_tuning"), null, setOf(), false),
    PhoneSettingReference("lyrics_animation_style", Res.string.m6_line_bounce, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION, setOf("settings_lyrics_line_bounce"), null, setOf(Res.string.m6_line_bounce), false),
    PhoneSettingReference("lyrics_adaptive_scroll_spring", Res.string.m6_adaptive_scroll, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION, setOf("settings_lyrics_adaptive_scroll"), null, setOf(Res.string.m6_adaptive_scroll), false),
    PhoneSettingReference("lyrics_soft_word_reveal", Res.string.m6_soft_reveal, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("settings_lyrics_soft_reveal"), null, setOf(Res.string.m6_soft_reveal), false),
    PhoneSettingReference("lyrics_emphasize_last_word", Res.string.phone_reference_settings_lyrics_last_word, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION, setOf("settings_lyrics_last_word"), null, setOf(), false),
    PhoneSettingReference("lyrics_interlude_dots", Res.string.m6_interlude_dots, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("settings_lyrics_interlude"), null, setOf(Res.string.m6_interlude_dots), false),
    PhoneSettingReference("lyrics_scale_animation", Res.string.m6_bounce_amplitude, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION, setOf("settings_lyrics_bounce_amplitude"), null, setOf(Res.string.m6_bounce_amplitude), false),
    PhoneSettingReference("lyrics_glow_animation", Res.string.m6_glow_intensity, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("settings_lyrics_glow"), null, setOf(Res.string.m6_glow_intensity), false),
    PhoneSettingReference("lyrics_fade_animation", Res.string.m6_fill_smoothness, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("settings_lyrics_fill"), null, setOf(Res.string.m6_fill_smoothness), false),
))
