package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneBackupAndRestoreReference = PhoneSettingsReference(Res.string.m6_settings_home_backup_restore, listOf(
    PhoneSettingReference("group:internal_service", Res.string.m6_backup_section_internal, null, PhoneSettingKind.HEADER, setOf("internal_service"), null, setOf(Res.string.m6_backup_section_internal), false),
    PhoneSettingReference("action_backup", Res.string.m6_backup_action_create, Res.drawable.phone_reference_backup, PhoneSettingKind.ACTION, setOf("action_backup"), null, setOf(Res.string.m6_backup_action_create), false),
    PhoneSettingReference("restore", Res.string.desktop_shell_window_restore, Res.drawable.phone_reference_restore, PhoneSettingKind.ACTION, setOf("action_restore"), null, setOf(Res.string.desktop_shell_window_restore, Res.string.m6_backup_action_restore), false),
    PhoneSettingReference("import_online", Res.string.m6_playlist_import_m3u_title, Res.drawable.phone_reference_playlist_import, PhoneSettingKind.ACTION, setOf("import_online"), null, setOf(Res.string.m6_playlist_import_m3u_title), false),
    PhoneSettingReference("import_csv", Res.string.m6_playlist_import_csv_title, Res.drawable.phone_reference_playlist_add, PhoneSettingKind.ACTION, setOf("import_csv"), null, setOf(Res.string.m6_playlist_import_csv_title), false),
    PhoneSettingReference("export_playlist_csv", Res.string.m4_export_csv, Res.drawable.phone_reference_download, PhoneSettingKind.ACTION, setOf("export_playlist_csv"), null, setOf(Res.string.m4_export_csv, Res.string.m6_playlist_export_csv_title), false),
    PhoneSettingReference("group:scheduled_backup", Res.string.m6_scheduled_backup_title, null, PhoneSettingKind.HEADER, setOf("scheduled_backup"), null, setOf(Res.string.m6_scheduled_backup_title), false),
    PhoneSettingReference("scheduled_backup_enabled", Res.string.m6_scheduled_backup_enabled, Res.drawable.phone_reference_repeat_on, PhoneSettingKind.TOGGLE, setOf("scheduled_backup_enabled"), null, setOf(Res.string.m6_scheduled_backup_enabled), false),
    PhoneSettingReference("scheduled_backup_frequency", Res.string.m6_scheduled_backup_frequency, Res.drawable.phone_reference_calendar_today, PhoneSettingKind.VALUE, setOf("scheduled_backup_frequency"), null, setOf(Res.string.m6_scheduled_backup_frequency), false),
    PhoneSettingReference("scheduled_backup_directory", Res.string.m6_scheduled_backup_directory, Res.drawable.phone_reference_snippet_folder, PhoneSettingKind.ACTION, setOf("scheduled_backup_directory"), null, setOf(Res.string.m6_scheduled_backup_directory), false),
    PhoneSettingReference("scheduled_backup_overwrite", Res.string.m6_scheduled_backup_overwrite, Res.drawable.phone_reference_backup, PhoneSettingKind.TOGGLE, setOf("scheduled_backup_overwrite"), null, setOf(Res.string.m6_scheduled_backup_overwrite), false),
    PhoneSettingReference("group:google_drive_sync", Res.string.m6_drive_sync_title, null, PhoneSettingKind.HEADER, setOf("google_drive_sync"), null, setOf(Res.string.m6_drive_sync_title), false),
    PhoneSettingReference("google_drive_sync_remote_folder", Res.string.m6_drive_sync_folder, Res.drawable.phone_reference_snippet_folder, PhoneSettingKind.ACTION, setOf("google_drive_sync_remote_folder"), null, setOf(Res.string.m6_drive_sync_folder, Res.string.m6_drive_sync_folder_default), false),
    PhoneSettingReference("google_drive_sync_clear_folder", Res.string.m6_drive_sync_clear_folder, Res.drawable.phone_reference_close, PhoneSettingKind.ACTION, setOf("google_drive_sync_clear_folder"), null, setOf(Res.string.m6_drive_sync_clear_folder), true),
    PhoneSettingReference("google_drive_sync_enabled", Res.string.m6_drive_sync_enabled, Res.drawable.phone_reference_sync, PhoneSettingKind.TOGGLE, setOf("google_drive_sync_enabled"), null, setOf(Res.string.m6_drive_sync_enabled), false),
    PhoneSettingReference("google_drive_sync_frequency", Res.string.m6_scheduled_backup_frequency, Res.drawable.phone_reference_calendar_today, PhoneSettingKind.VALUE, setOf("scheduled_backup_frequency"), null, setOf(Res.string.m6_scheduled_backup_frequency), false),
    PhoneSettingReference("google_drive_sync_overwrite", Res.string.m6_drive_sync_overwrite, Res.drawable.phone_reference_backup, PhoneSettingKind.TOGGLE, setOf("google_drive_sync_overwrite"), null, setOf(Res.string.m6_drive_sync_overwrite), false),
    PhoneSettingReference("google_drive_sync_run_now", Res.string.m6_drive_sync_run_now, Res.drawable.phone_reference_sync, PhoneSettingKind.ACTION, setOf("google_drive_sync_run_now"), null, setOf(Res.string.m6_drive_sync_run_now), false),
))
