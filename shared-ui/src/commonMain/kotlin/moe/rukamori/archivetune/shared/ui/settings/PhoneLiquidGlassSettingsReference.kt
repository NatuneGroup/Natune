package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLiquidGlassSettingsReference = PhoneSettingsReference(Res.string.phone_reference_liquid_glass_settings_title, listOf(
    PhoneSettingReference("liquid_glass_customisation", Res.string.phone_reference_liquid_glass_settings_title, Res.drawable.phone_reference_tune, PhoneSettingKind.ACTION, setOf("liquid_glass_settings_title"), null, setOf(), false),
    PhoneSettingReference("group:general", Res.string.m6_account_general_group, null, PhoneSettingKind.HEADER, setOf("general"), null, setOf(Res.string.m6_account_general_group, Res.string.m6_integration_general, Res.string.m6_liquid_glass_general), false),
    PhoneSettingReference("liquid_glass_effects", Res.string.m6_liquid_glass, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("liquid_glass_effects"), null, setOf(Res.string.m6_liquid_glass, Res.string.m6_liquid_glass_effects), true),
    PhoneSettingReference("glass_intensity", Res.string.phone_reference_liquid_glass_intensity, Res.drawable.phone_reference_graphic_eq, PhoneSettingKind.VALUE, setOf("liquid_glass_intensity"), "liquidGlassIntensity", setOf(), false),
    PhoneSettingReference("group:liquid_glass_optics_group", Res.string.m6_liquid_glass_optics, null, PhoneSettingKind.HEADER, setOf("liquid_glass_optics_group"), null, setOf(Res.string.m6_liquid_glass_optics), false),
    PhoneSettingReference("group:liquid_glass_quality_group", Res.string.phone_reference_liquid_glass_quality_group, null, PhoneSettingKind.HEADER, setOf("liquid_glass_quality_group"), null, setOf(), false),
    PhoneSettingReference("glass_depth_3d", Res.string.phone_reference_liquid_glass_depth_3d, Res.drawable.phone_reference_sliders, PhoneSettingKind.TOGGLE, setOf("liquid_glass_depth_3d"), "liquidGlassDepth3D", setOf(), false),
    PhoneSettingReference("glass_chromatic_aberration", Res.string.m6_liquid_glass_chromatic_aberration, Res.drawable.phone_reference_contrast, PhoneSettingKind.TOGGLE, setOf("liquid_glass_chromatic_aberration"), "liquidGlassChromaticAberration", setOf(Res.string.m6_liquid_glass_chromatic_aberration), false),
    PhoneSettingReference("glass_backdrop_vibrancy", Res.string.m6_liquid_glass_backdrop_vibrancy, Res.drawable.phone_reference_palette, PhoneSettingKind.TOGGLE, setOf("liquid_glass_backdrop_vibrancy"), "liquidGlassBackdropVibrancy", setOf(Res.string.m6_liquid_glass_backdrop_vibrancy), false),
    PhoneSettingReference("glass_adaptive_luminance", Res.string.m6_liquid_glass_adaptive_luminance, Res.drawable.phone_reference_solar_brightness_auto_linear, PhoneSettingKind.TOGGLE, setOf("liquid_glass_adaptive_luminance"), "liquidGlassAdaptiveLuminance", setOf(Res.string.m6_liquid_glass_adaptive_luminance), false),
    PhoneSettingReference("group:liquid_glass_reset_group", Res.string.ios_lyrics_reset, null, PhoneSettingKind.HEADER, setOf("liquid_glass_reset_group"), null, setOf(Res.string.ios_lyrics_reset, Res.string.m5pq_reset, Res.string.m6_listen_reset, Res.string.m6_liquid_glass_reset_group, Res.string.content_lyrics_offset_reset), false),
    PhoneSettingReference("glass_reset", Res.string.phone_reference_liquid_glass_reset, Res.drawable.phone_reference_restore, PhoneSettingKind.ACTION, setOf("liquid_glass_reset"), null, setOf(), false),
))
