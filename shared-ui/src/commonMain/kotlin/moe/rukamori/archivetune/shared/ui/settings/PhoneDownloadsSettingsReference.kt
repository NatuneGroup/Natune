package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneDownloadsSettingsReference = PhoneSettingsReference(Res.string.m4_downloads, listOf(
    PhoneSettingReference("group:downloaded_songs", Res.string.phone_reference_downloaded_songs, null, PhoneSettingKind.HEADER, setOf("downloaded_songs"), null, setOf(), false),
    PhoneSettingReference("clear_all_downloads", Res.string.m6_clear_downloads, Res.drawable.phone_reference_ic_download, PhoneSettingKind.ACTION, setOf("clear_all_downloads"), null, setOf(Res.string.m6_clear_downloads), false),
    PhoneSettingReference("export_downloaded_songs", Res.string.ios_export_songs_export, Res.drawable.phone_reference_send, PhoneSettingKind.ACTION, setOf("export_downloaded_songs"), null, setOf(Res.string.ios_export_songs_export, Res.string.m11_export_logs, Res.string.m6_export_download), false),
    PhoneSettingReference("group:downloads", Res.string.m4_downloads, null, PhoneSettingKind.HEADER, setOf("downloads"), null, setOf(Res.string.m4_downloads, Res.string.m6_settings_home_downloads, Res.string.m6_downloads), false),
    PhoneSettingReference("auto_download_on_like", Res.string.phone_reference_auto_download_on_like, Res.drawable.phone_reference_download, PhoneSettingKind.TOGGLE, setOf("auto_download_on_like"), "autoDownloadOnLike", setOf(), false),
    PhoneSettingReference("download_source", Res.string.m6_download_sources, Res.drawable.phone_reference_download, PhoneSettingKind.ACTION, setOf("download_source_title"), null, setOf(Res.string.m6_download_sources), false),
    PhoneSettingReference("group:external_downloader", Res.string.m6_external_downloader, null, PhoneSettingKind.HEADER, setOf("external_downloader"), null, setOf(Res.string.m6_external_downloader), false),
    PhoneSettingReference("external_downloader", Res.string.m6_external_downloader, Res.drawable.phone_reference_download, PhoneSettingKind.TOGGLE, setOf("external_downloader"), "externalDownloaderEnabled", setOf(Res.string.m6_external_downloader), false),
    PhoneSettingReference("external_downloader_package", Res.string.phone_reference_external_downloader_package, Res.drawable.phone_reference_integration, PhoneSettingKind.ACTION, setOf("external_downloader_package"), null, setOf(), false),
))
