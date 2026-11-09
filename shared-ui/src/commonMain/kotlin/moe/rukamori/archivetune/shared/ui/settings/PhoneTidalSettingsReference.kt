package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneTidalSettingsReference = PhoneSettingsReference(Res.string.ios_lane_artwork_tidal, listOf(
    PhoneSettingReference("group:tidal_artwork", Res.string.phone_reference_tidal_artwork, null, PhoneSettingKind.HEADER, setOf("tidal_artwork"), null, setOf(), false),
    PhoneSettingReference("tidal_animated_covers", Res.string.phone_reference_tidal_animated_covers, Res.drawable.phone_reference_image, PhoneSettingKind.TOGGLE, setOf("tidal_animated_covers"), "tidalAnimatedCoversEnabled", setOf(), false),
    PhoneSettingReference("tidal_artwork_fallback", Res.string.phone_reference_tidal_artwork_fallback, Res.drawable.phone_reference_image, PhoneSettingKind.TOGGLE, setOf("tidal_artwork_fallback"), "tidalArtworkFallbackEnabled", setOf(), false),
    PhoneSettingReference("group:tidal_account", Res.string.m6_tidal_account_group, null, PhoneSettingKind.HEADER, setOf("tidal_account"), null, setOf(Res.string.m6_tidal_account_group), false),
    PhoneSettingReference("tidal_account_connected", Res.string.phone_reference_tidal_account_connected, Res.drawable.phone_reference_error, PhoneSettingKind.ACTION, setOf("tidal_account_connected"), null, setOf(), true),
    PhoneSettingReference("tidal_reconnect", Res.string.m6_tidal_reconnect, Res.drawable.phone_reference_error, PhoneSettingKind.ACTION, setOf("tidal_reconnect"), null, setOf(Res.string.m6_tidal_reconnect), true),
    PhoneSettingReference("tidal_disconnect", Res.string.m6_tidal_disconnect, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("tidal_disconnect"), null, setOf(Res.string.m6_tidal_disconnect), true),
    PhoneSettingReference("tidal_login_web", Res.string.m6_tidal_login_web, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("tidal_login_web"), null, setOf(Res.string.m6_tidal_login_web), false),
    PhoneSettingReference("tidal_paste_token", Res.string.m6_tidal_paste_token, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("sources_paste_token"), null, setOf(Res.string.m6_tidal_paste_token), false),
    PhoneSettingReference("group:tidal_instances", Res.string.m6_tidal_instances_group, null, PhoneSettingKind.HEADER, setOf("tidal_instances"), null, setOf(Res.string.m6_tidal_instances_group), false),
    PhoneSettingReference("source_manage_instances", Res.string.m6_qobuz_manage_instances, Res.drawable.phone_reference_tune, PhoneSettingKind.ACTION, setOf("source_manage_instances"), null, setOf(Res.string.m6_qobuz_manage_instances, Res.string.m6_tidal_manage_instances), false),
    PhoneSettingReference("tidal_checking_instances", Res.string.m6_qobuz_checking_instances, Res.drawable.phone_reference_sync, PhoneSettingKind.ACTION, setOf("tidal_checking_instances"), null, setOf(Res.string.m6_qobuz_checking_instances, Res.string.m6_tidal_checking_instances), false),
    PhoneSettingReference("tidal_add_instance", Res.string.m6_qobuz_add_instance, Res.drawable.phone_reference_add, PhoneSettingKind.ACTION, setOf("tidal_add_instance"), null, setOf(Res.string.m6_qobuz_add_instance, Res.string.m6_tidal_add_instance), true),
    PhoneSettingReference("source_bulk_add", Res.string.m6_qobuz_bulk_add, Res.drawable.phone_reference_playlist_add, PhoneSettingKind.ACTION, setOf("source_bulk_add"), null, setOf(Res.string.m6_qobuz_bulk_add, Res.string.m6_tidal_bulk_add), true),
    PhoneSettingReference("source_copy_online", Res.string.m6_qobuz_copy_online, Res.drawable.phone_reference_copy, PhoneSettingKind.ACTION, setOf("source_copy_online"), null, setOf(Res.string.m6_qobuz_copy_online, Res.string.m6_tidal_copy_online), true),
    PhoneSettingReference("source_remove_dead", Res.string.m6_qobuz_remove_dead, Res.drawable.phone_reference_delete, PhoneSettingKind.ACTION, setOf("source_remove_dead"), null, setOf(Res.string.m6_qobuz_remove_dead, Res.string.m6_tidal_remove_dead), true),
    PhoneSettingReference("source_remove_deprecated", Res.string.m6_qobuz_remove_deprecated, Res.drawable.phone_reference_delete, PhoneSettingKind.ACTION, setOf("source_remove_deprecated"), null, setOf(Res.string.m6_qobuz_remove_deprecated, Res.string.m6_tidal_remove_deprecated), true),
    PhoneSettingReference("tidal_reset_instances", Res.string.m6_qobuz_reset_instances, Res.drawable.phone_reference_close, PhoneSettingKind.ACTION, setOf("tidal_reset_instances"), null, setOf(Res.string.m6_qobuz_reset_instances, Res.string.m6_tidal_reset_instances), true),
))
