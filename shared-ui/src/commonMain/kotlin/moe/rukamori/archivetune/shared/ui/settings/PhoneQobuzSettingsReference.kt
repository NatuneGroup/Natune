package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneQobuzSettingsReference = PhoneSettingsReference(Res.string.m5pq_source_qobuz, listOf(
    PhoneSettingReference("group:qobuz_integration", Res.string.m5pq_source_qobuz, null, PhoneSettingKind.HEADER, setOf("qobuz_integration"), null, setOf(Res.string.m5pq_source_qobuz, Res.string.m6_dev_source_qobuz, Res.string.m6_integration_qobuz, Res.string.m6_qobuz_title, Res.string.m6_source_qobuz), false),
    PhoneSettingReference("qobuz_audio_quality", Res.string.m6_audio_quality, Res.drawable.phone_reference_graphic_eq, PhoneSettingKind.VALUE, setOf("qobuz_audio_quality"), "qobuzAudioQuality", setOf(Res.string.m6_audio_quality, Res.string.m6_qobuz_audio_quality), false),
    PhoneSettingReference("group:qobuz_tokens", Res.string.m6_qobuz_accounts_group, null, PhoneSettingKind.HEADER, setOf("qobuz_tokens"), null, setOf(Res.string.m6_qobuz_accounts_group), false),
    PhoneSettingReference("qobuz_login_web", Res.string.m6_qobuz_login_web, Res.drawable.phone_reference_provider_qobuz, PhoneSettingKind.ACTION, setOf("qobuz_login_web"), null, setOf(Res.string.m6_qobuz_login_web), false),
    PhoneSettingReference("qobuz_add_tokens", Res.string.m6_qobuz_add_tokens, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("qobuz_add_tokens"), null, setOf(Res.string.m6_qobuz_add_tokens), true),
    PhoneSettingReference("qobuz_checking_tokens", Res.string.m6_qobuz_checking_tokens, Res.drawable.phone_reference_sync, PhoneSettingKind.ACTION, setOf("qobuz_checking_tokens"), null, setOf(Res.string.m6_qobuz_checking_tokens), true),
    PhoneSettingReference("qobuz_manage_accounts", Res.string.m6_qobuz_manage_accounts, Res.drawable.phone_reference_tune, PhoneSettingKind.ACTION, setOf("qobuz_manage_accounts"), null, setOf(Res.string.m6_qobuz_manage_accounts), false),
    PhoneSettingReference("qobuz_token_subtitle", Res.string.m6_qobuz_account_row, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("qobuz_token_subtitle"), null, setOf(Res.string.m6_qobuz_account_row), true),
    PhoneSettingReference("qobuz_reset_tokens", Res.string.m6_qobuz_reset_tokens, Res.drawable.phone_reference_close, PhoneSettingKind.ACTION, setOf("qobuz_reset_tokens"), null, setOf(Res.string.m6_qobuz_reset_tokens), true),
    PhoneSettingReference("group:qobuz_instances", Res.string.m6_qobuz_instances_group, null, PhoneSettingKind.HEADER, setOf("qobuz_instances"), null, setOf(Res.string.m6_qobuz_instances_group), false),
    PhoneSettingReference("qobuz_checking_instances", Res.string.m6_qobuz_checking_instances, Res.drawable.phone_reference_sync, PhoneSettingKind.ACTION, setOf("qobuz_checking_instances"), null, setOf(Res.string.m6_qobuz_checking_instances, Res.string.m6_tidal_checking_instances), false),
    PhoneSettingReference("source_manage_instances", Res.string.m6_qobuz_manage_instances, Res.drawable.phone_reference_tune, PhoneSettingKind.ACTION, setOf("source_manage_instances"), null, setOf(Res.string.m6_qobuz_manage_instances, Res.string.m6_tidal_manage_instances), false),
    PhoneSettingReference("qobuz_add_instance", Res.string.m6_qobuz_add_instance, Res.drawable.phone_reference_add, PhoneSettingKind.ACTION, setOf("qobuz_add_instance"), null, setOf(Res.string.m6_qobuz_add_instance, Res.string.m6_tidal_add_instance), true),
    PhoneSettingReference("source_bulk_add", Res.string.m6_qobuz_bulk_add, Res.drawable.phone_reference_playlist_add, PhoneSettingKind.ACTION, setOf("source_bulk_add"), null, setOf(Res.string.m6_qobuz_bulk_add, Res.string.m6_tidal_bulk_add), true),
    PhoneSettingReference("source_copy_online", Res.string.m6_qobuz_copy_online, Res.drawable.phone_reference_copy, PhoneSettingKind.ACTION, setOf("source_copy_online"), null, setOf(Res.string.m6_qobuz_copy_online, Res.string.m6_tidal_copy_online), true),
    PhoneSettingReference("source_remove_dead", Res.string.m6_qobuz_remove_dead, Res.drawable.phone_reference_delete, PhoneSettingKind.ACTION, setOf("source_remove_dead"), null, setOf(Res.string.m6_qobuz_remove_dead, Res.string.m6_tidal_remove_dead), true),
    PhoneSettingReference("source_remove_deprecated", Res.string.m6_qobuz_remove_deprecated, Res.drawable.phone_reference_delete, PhoneSettingKind.ACTION, setOf("source_remove_deprecated"), null, setOf(Res.string.m6_qobuz_remove_deprecated, Res.string.m6_tidal_remove_deprecated), true),
    PhoneSettingReference("qobuz_reset_instances", Res.string.m6_qobuz_reset_instances, Res.drawable.phone_reference_close, PhoneSettingKind.ACTION, setOf("qobuz_reset_instances"), null, setOf(Res.string.m6_qobuz_reset_instances, Res.string.m6_tidal_reset_instances), true),
))
