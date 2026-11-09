package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneContentSettingsReference = PhoneSettingsReference(Res.string.m6_android_auto_content, listOf(
    PhoneSettingReference("group:general", Res.string.m6_account_general_group, null, PhoneSettingKind.HEADER, setOf("general"), null, setOf(Res.string.m6_account_general_group, Res.string.m6_integration_general, Res.string.m6_liquid_glass_general), false),
    PhoneSettingReference("content_language", Res.string.phone_reference_content_language, Res.drawable.phone_reference_language, PhoneSettingKind.VALUE, setOf("content_language"), "contentLanguage", setOf(), false),
    PhoneSettingReference("content_country", Res.string.phone_reference_content_country, Res.drawable.phone_reference_location_on, PhoneSettingKind.VALUE, setOf("content_country"), "contentCountry", setOf(), false),
    PhoneSettingReference("you_might_like_source", Res.string.phone_reference_you_might_like_source, Res.drawable.phone_reference_playlist_play, PhoneSettingKind.VALUE, setOf("you_might_like_source", "playlist_suggestion_source"), "playlistSuggestionSource", setOf(), false),
    PhoneSettingReference("hide_explicit", Res.string.m6_hide_explicit, Res.drawable.phone_reference_explicit, PhoneSettingKind.TOGGLE, setOf("hide_explicit"), "hideExplicit", setOf(Res.string.m6_hide_explicit), false),
    PhoneSettingReference("hide_video", Res.string.phone_reference_hide_video, Res.drawable.phone_reference_slow_motion_video, PhoneSettingKind.TOGGLE, setOf("hide_video"), "hideVideo", setOf(), false),
    PhoneSettingReference("allow_age_restricted", Res.string.phone_reference_allow_age_restricted, Res.drawable.phone_reference_login, PhoneSettingKind.TOGGLE, setOf("allow_age_restricted"), "allowAgeRestricted", setOf(), false),
    PhoneSettingReference("group:app_language", Res.string.phone_reference_app_language, null, PhoneSettingKind.HEADER, setOf("app_language"), null, setOf(), false),
    PhoneSettingReference("app_language", Res.string.phone_reference_app_language, Res.drawable.phone_reference_language, PhoneSettingKind.ACTION, setOf("app_language"), null, setOf(), false),
    PhoneSettingReference("group:misc", Res.string.m6_account_misc_group, null, PhoneSettingKind.HEADER, setOf("misc"), null, setOf(Res.string.m6_account_misc_group), false),
    PhoneSettingReference("hide_ai_mix", Res.string.m6_hide_ai_mix, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.TOGGLE, setOf("hide_ai_mix"), "hide_ai_mix", setOf(Res.string.m6_hide_ai_mix), false),
    PhoneSettingReference("group:ai_content_filter", Res.string.phone_reference_ai_content_filter, null, PhoneSettingKind.HEADER, setOf("ai_content_filter"), null, setOf(), false),
    PhoneSettingReference("ai_content_filter", Res.string.phone_reference_ai_content_filter, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.ACTION, setOf("ai_content_filter"), null, setOf(), false),
    PhoneSettingReference("ai_content_filter_hide", Res.string.phone_reference_ai_content_filter_hide, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.TOGGLE, setOf("ai_content_filter_hide"), null, setOf(), false),
    PhoneSettingReference("ai_content_filter_moderate", Res.string.phone_reference_ai_content_filter_moderate, Res.drawable.phone_reference_filter_alt, PhoneSettingKind.TOGGLE, setOf("ai_content_filter_moderate"), null, setOf(), false),
    PhoneSettingReference("ai_content_filter_update", Res.string.phone_reference_ai_content_filter_update, Res.drawable.phone_reference_sync, PhoneSettingKind.ACTION, setOf("ai_content_filter_update"), null, setOf(), false),
    PhoneSettingReference("ai_content_filter_source", Res.string.phone_reference_ai_content_filter_source, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("ai_content_filter_source"), null, setOf(), false),
))
