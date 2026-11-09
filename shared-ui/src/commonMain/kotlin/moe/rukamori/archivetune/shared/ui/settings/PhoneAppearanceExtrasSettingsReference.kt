package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAppearanceExtrasSettingsReference = PhoneSettingsReference(Res.string.m6_extras, listOf(
    PhoneSettingReference("group:extras", Res.string.m6_extras, null, PhoneSettingKind.HEADER, setOf("extras"), null, setOf(Res.string.m6_extras), false),
    PhoneSettingReference("show_home_category_chips", Res.string.m6_home_categories, Res.drawable.phone_reference_home_outlined, PhoneSettingKind.TOGGLE, setOf("show_home_category_chips"), "showHomeCategoryChips", setOf(Res.string.m6_home_categories), false),
    PhoneSettingReference("show_tags_in_library", Res.string.m6_library_tags, Res.drawable.phone_reference_filter_alt, PhoneSettingKind.TOGGLE, setOf("show_tags_in_library"), "showTagsInLibrary", setOf(Res.string.m6_library_tags), false),
    PhoneSettingReference("hide_liked_songs_card", Res.string.m6_hide_liked, Res.drawable.phone_reference_favorite, PhoneSettingKind.TOGGLE, setOf("hide_liked_songs_card"), "hide_liked_songs_card", setOf(Res.string.m6_hide_liked), false),
    PhoneSettingReference("hide_offline_card", Res.string.m6_hide_offline, Res.drawable.phone_reference_offline, PhoneSettingKind.TOGGLE, setOf("hide_offline_card"), "hide_offline_card", setOf(Res.string.m6_hide_offline), false),
    PhoneSettingReference("hide_cached_card", Res.string.m6_hide_cached, Res.drawable.phone_reference_cached, PhoneSettingKind.TOGGLE, setOf("hide_cached_card"), "hide_cached_card", setOf(Res.string.m6_hide_cached), false),
    PhoneSettingReference("hide_local_files_card", Res.string.m6_hide_local, Res.drawable.phone_reference_snippet_folder, PhoneSettingKind.TOGGLE, setOf("hide_local_files_card"), "hide_local_files_card", setOf(Res.string.m6_hide_local), false),
    PhoneSettingReference("hide_top50_card", Res.string.phone_reference_hide_top50_card, Res.drawable.phone_reference_trending_up, PhoneSettingKind.TOGGLE, setOf("hide_top50_card"), "hide_top50_card", setOf(), false),
))
