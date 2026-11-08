/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.ColorScheme
import androidx.navigation.NavController
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoDownloadOnLikeKey
import moe.rukamori.archivetune.constants.DisableScreenshotKey
import moe.rukamori.archivetune.constants.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.constants.ForceHighRefreshRateKey
import moe.rukamori.archivetune.constants.HideAiMixKey
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.LowDataModeKey
import moe.rukamori.archivetune.constants.PauseListenHistoryKey
import moe.rukamori.archivetune.constants.PauseSearchHistoryKey
import moe.rukamori.archivetune.constants.ProxyEnabledKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.SyncPlaybackToYouTubeHistoryKey

fun buildSettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    context: Context,
    colorScheme: ColorScheme,
): List<SettingsGroup> {
    val account =
        SettingsItem(
            key = "account",
            icon = R.drawable.ic_account,
            title = context.getString(R.string.account),
            subtitle = context.getString(R.string.settings_account_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("account", "profile", "youtube", "sign in", "login", "logout", "token", "hidden", "playlist", "channels", "switch account"),
            onClick = { navController.navigate("settings/account") },
            children = listOf(
                SettingsChild("Account switcher", "account_switcher", listOf("account switcher", "switch account", "multiple accounts", "saved accounts", "account channels")),
                SettingsChild("Hidden playlists", "hidden_playlists", listOf("hidden", "hidden playlists", "hide playlist", "hidden music", "private playlist")),
                SettingsChild("Tap to show tokens", "tap_to_show_tokens", listOf("token", "tokens", "show token", "po token", "innertube", "visitor data", "datasync", "credentials", "advanced login")),
                SettingsChild("Save current account", "save_current_account", listOf("save account", "remember account", "persist account")),
                SettingsChild("Logout", "account_logout", listOf("logout", "log out", "sign out", "disconnect")),
            ),
        )
    val modules = SettingsItem(
        key = "modules", icon = R.drawable.extension,
        title = context.getString(R.string.modules_title),
        subtitle = context.getString(R.string.modules_subtitle), accentColor = colorScheme.primary,
        keywords = listOf("modules", "extensions", "catalog", "arch", "sources"),
        onClick = { navController.navigate("settings/modules") },
    )
    val stats =
        SettingsItem(
            key = "stats",
            icon = R.drawable.stats,
            title = context.getString(R.string.settings_stats_title),
            subtitle = context.getString(R.string.settings_stats_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("stats", "statistics", "listening", "history", "top", "most played", "time"),
            onClick = { navController.navigate("stats") },
        )
    val listenTogether =
        SettingsItem(
            key = "listen_together",
            icon = R.drawable.diversity_listen_together,
            title = context.getString(R.string.listen_together),
            subtitle = context.getString(R.string.setting_listen_together_desc),
            accentColor = colorScheme.primary,
            keywords = listOf("listen together", "room", "shared playback", "sync playback", "listen party", "group listening", "chat", "friends"),
            onClick = { navController.navigate("listen_together") },
        )
    val appearance = appearanceSettingsItem(navController, context, colorScheme)
    val appearanceExtras = appearanceExtrasSettingsItem(navController, colorScheme)
    val aodCustomization = aodCustomizationSettingsItem(navController, colorScheme)
    val navigationBar = navigationBarSettingsItem(navController, colorScheme)
    val lyricsAnimations = lyricsAnimationsSettingsItem(navController, colorScheme)
    val playback = playbackSettingsItem(navController, context, colorScheme)
    val canvas = canvasSettingsItem(navController, context, colorScheme)
    val jioSaavn = jioSaavnSettingsItem(navController, colorScheme)
    val amazon = amazonSettingsItem(navController, colorScheme)
    val qqMusic = qqMusicSettingsItem(navController, colorScheme)
    val spotifyStreaming = spotifyStreamingSettingsItem(navController, colorScheme)
    val deezer = deezerSettingsItem(navController, colorScheme)
    val sources = sourcesSettingsItem(navController, context, colorScheme)
    val lyrics = lyricsSettingsItem(navController, context, colorScheme)
    val lyricsProviders = lyricsProvidersSettingsItem(navController, colorScheme)
    val lyricsRomanisation = lyricsRomanisationSettingsItem(navController, colorScheme)
    val content =
        SettingsItem(
            key = "content",
            icon = R.drawable.language,
            title = context.getString(R.string.content),
            subtitle = context.getString(R.string.settings_content_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("content", "language", "locale", "country", "region", "app language", "explicit", "age restricted", "age", "mature", "video", "progressive"),
            onClick = { navController.navigate("settings/content") },
            children = listOf(
                SettingsChild("Content language", "content_language", listOf("language", "content language", "locale", "country")),
                SettingsChild("Content country", "content_country", listOf("country", "region", "content country")),
                SettingsChild("Hide explicit", "hide_explicit", listOf("explicit", "age", "mature", "age restricted", "clean")) { SearchResultSwitch(HideExplicitKey, false) },
                SettingsChild("Hide video", "hide_video", listOf("video", "hide video", "music video", "mv")) { SearchResultSwitch(HideVideoKey, false) },
                SettingsChild("Enable video", "enable_video", listOf("video", "music video", "mv", "enable video")),
                SettingsChild("Progressive playback", "progressive_playback", listOf("progressive", "gapless", "seamless")),
                SettingsChild("Allow age-restricted content", "allow_age_restricted", listOf("age restricted", "allow age restricted", "mature content", "18+", "restricted")),
                SettingsChild("Playlist recommendation source", "you_might_like_source", listOf("recommendation source", "you might like", "playlist recommendation", "suggestions source")),
                SettingsChild("AI content filter", "ai_content_filter", listOf("ai content filter", "ai filter", "ai generated", "aislist", "filter ai music")),
                SettingsChild("Hide AI-generated content", "ai_content_filter_hide", listOf("hide ai generated", "hide ai music", "ai content hide", "block ai")),
                SettingsChild("Include moderate-confidence channels", "ai_content_filter_moderate", listOf("moderate confidence", "ai filter moderate", "ai channels")),
                SettingsChild("Update channel lists", "ai_content_filter_update", listOf("update channel lists", "refresh ai list", "aislist update")),
                SettingsChild("About AiSList", "ai_content_filter_source", listOf("aislist", "about aislist", "ai list source")),
                SettingsChild("Hide AI mix", "hide_ai_mix", listOf("hide ai", "ai mix", "smart mix", "hide mix")) { SearchResultSwitch(HideAiMixKey, false) },
            ),
        )
    val languagePacks =
        SettingsItem(
            key = "language_packs",
            icon = R.drawable.translate,
            title = context.getString(R.string.additional_downloads),
            subtitle = context.getString(R.string.additional_downloads_subtitle),
            accentColor = colorScheme.secondary,
            keywords = listOf("additional downloads", "additional resources", "language pack", "translation", "translate", "localization", "i18n", "dictionary", "japanese", "fonts", "spatialflow font", "addons"),
            onClick = { navController.navigate("settings/language_packs") },
        )
    val behavior =
        SettingsItem(
            key = "behavior",
            icon = R.drawable.swipe,
            title = context.getString(R.string.settings_behavior_title),
            subtitle = context.getString(R.string.settings_behavior_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("behavior", "privacy", "swipe", "gesture", "history", "cache", "data", "screenshot", "haptic", "vibrate"),
            onClick = { navController.navigate("settings/privacy") },
            children = listOf(
                SettingsChild("Pause listen history", "pause_listen_history", listOf("pause listen", "stop history", "private listening")) { SearchResultSwitch(PauseListenHistoryKey, false) },
                SettingsChild("Clear listen history", "clear_listen_history", listOf("clear history", "delete history", "reset history")),
                SettingsChild("Pause search history", "pause_search_history", listOf("pause search", "stop search history", "private search")) { SearchResultSwitch(PauseSearchHistoryKey, false) },
                SettingsChild("Clear search history", "clear_search_history", listOf("clear search", "delete search", "reset search")),
                SettingsChild("Sync playback to YouTube history", "sync_yt_history", listOf("youtube history", "sync history", "playback history")) { SearchResultSwitch(SyncPlaybackToYouTubeHistoryKey, moe.rukamori.archivetune.shared.domain.settings.PrivacyPreferenceDefaults.SYNC_YOUTUBE_HISTORY) },
                SettingsChild("Haptics", "haptics", listOf("haptic", "vibration", "haptic feedback", "vibrate")) { SearchResultSwitch(EnableHapticFeedbackKey, true) },
                SettingsChild("Disable screenshot", "disable_screenshot", listOf("screenshot", "screen capture", "privacy", "no screenshot")) { SearchResultSwitch(DisableScreenshotKey, false) },
                SettingsChild("Show tags in library", "show_tags_in_library", listOf("tags", "library tags", "show tags")),
                SettingsChild("Low data mode", "low_data_mode", listOf("low data", "data saver", "save data", "metered", "data mode")) { SearchResultSwitch(LowDataModeKey, true) },
                SettingsChild("Force high refresh rate", "force_high_refresh_rate", listOf("refresh rate", "high refresh", "120hz", "90hz", "smooth")) { SearchResultSwitch(ForceHighRefreshRateKey, false) },
                SettingsChild("Open supported links by default", "open_supported_links", listOf("open links", "supported links", "default links", "deep link", "default browser app")),
            ),
        )
    val integration = integrationSettingsItem(navController, context, colorScheme)
    val discordExperimental = discordExperimentalSettingsItem(navController, colorScheme)
    val tidalDetail = tidalDetailSettingsItem(navController, colorScheme)
    val qobuzDetail = qobuzDetailSettingsItem(navController, colorScheme)
    val telegramDetail = telegramDetailSettingsItem(navController, colorScheme)
    val androidAuto = androidAutoSettingsItem(navController, context, colorScheme)
    val aiIntegration = aiIntegrationSettingsItem(navController, context, colorScheme)
    val internet =
        SettingsItem(
            key = "internet",
            icon = R.drawable.wifi_proxy,
            title = context.getString(R.string.internet),
            subtitle = context.getString(R.string.settings_internet_subtitle),
            accentColor = colorScheme.tertiary,
            keywords = listOf("internet", "proxy", "vpn", "network", "wifi", "connection", "traffic", "dns", "dns over https", "region", "country", "spoof", "geobypass", "geo bypass"),
            onClick = { navController.navigate("settings/internet") },
            children = listOf(
                SettingsChild("YouTube Music region", "yt_music_region", listOf("region", "country", "gl", "geo", "location", "spoof", "youtube region", "youtube music country", "geobypass", "geo bypass")),
                SettingsChild("Proxy", "proxy_settings", listOf("proxy", "http proxy", "socks", "vpn")) { SearchResultSwitch(ProxyEnabledKey, false) },
                SettingsChild("Proxy host", "proxy_host", listOf("proxy host", "proxy address", "proxy server")),
                SettingsChild("Proxy port", "proxy_port", listOf("proxy port", "port", "proxy port number")),
                SettingsChild("Proxy type", "proxy_type", listOf("proxy type", "socks5", "http proxy type", "proxy protocol")),
                SettingsChild("Proxy username", "proxy_username", listOf("proxy username", "proxy auth", "proxy credentials", "proxy login")),
                SettingsChild("Proxy password", "proxy_password", listOf("proxy password", "proxy auth", "proxy credentials", "proxy secret")),
                SettingsChild("Bypass proxy for streams", "stream_bypass_proxy", listOf("bypass proxy", "stream proxy", "stream bypass", "skip proxy for streams")),
                SettingsChild("Test proxy connection", "test_proxy", listOf("test proxy", "verify proxy", "check proxy", "proxy test")),
                SettingsChild("DNS over HTTPS", "dns_over_https", listOf("dns", "dns over https", "doh", "encrypted dns", "secure dns")),
                SettingsChild("DNS provider", "dns_provider", listOf("dns provider", "dns server", "dns resolver", "dns service")),
                SettingsChild("DNS custom URL", "dns_custom_url", listOf("dns custom url", "custom dns", "dns endpoint", "dns url")),
                SettingsChild("IP rotation", "ip_rotation", listOf("ip rotation", "rotate ip", "ip pool", "ip cycling")),
            ),
        )
    val poToken =
        SettingsItem(
            key = "po_token",
            icon = R.drawable.token,
            title = context.getString(R.string.po_token_generation),
            subtitle = context.getString(R.string.settings_po_token_subtitle),
            accentColor = colorScheme.secondary,
            keywords = listOf("po token", "potoken", "botguard", "youtube token", "playability"),
            onClick = { navController.navigate(PO_TOKEN_ROUTE) },
            // Accounts sub-page anchor, kept in the search index so existing search shortcuts still
            // work.
            hidden = true,
            children = listOf(
                SettingsChild("Web Client PO Token", "web_client_po_token", listOf("po token", "potoken", "web client po token", "botguard", "playability", "youtube token")),
            ),
        )

    val storage =
        SettingsItem(
            key = "storage",
            icon = R.drawable.storage,
            title = context.getString(R.string.storage),
            subtitle = context.getString(R.string.settings_storage_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("storage", "download", "cache", "disk", "space", "memory", "path", "location", "export", "export songs", "local storage", "save songs"),
            onClick = { navController.navigate("settings/storage") },
            children = listOf(
                SettingsChild("Downloaded songs", "downloaded_songs", listOf("downloaded", "offline songs", "saved songs")),
                SettingsChild("Song cache size", "song_cache_size", listOf("cache size", "song cache", "memory", "download cache")),
                SettingsChild("Clear song cache", "clear_song_cache", listOf("clear song cache", "delete song cache", "wipe song cache")),
                SettingsChild("Image cache size", "image_cache_size", listOf("image cache", "thumbnail cache", "artwork cache")),
                SettingsChild("Clear image cache", "clear_image_cache", listOf("clear image cache", "delete image cache", "wipe image cache")),
                SettingsChild("Canvas cache", "canvas_cache", listOf("canvas cache", "motion artwork cache", "animated artwork storage")),
                SettingsChild("Clear canvas cache", "clear_canvas_cache", listOf("clear canvas cache", "delete canvas cache", "wipe canvas cache")),
                SettingsChild("Storage folder", "storage_folder", listOf("storage path", "storage location", "storage directory")),
                SettingsChild("Download location", "download_location", listOf("download path", "location", "folder", "directory", "save to")),
                SettingsChild("Smart trimmer", "smart_trimmer", listOf("smart trimmer", "trim cache", "auto clean cache")) { SearchResultSwitch(SmartTrimmerKey, false) },
                SettingsChild("Max song cache size", "max_song_cache_size", listOf("max song cache", "song cache size", "cache limit", "cache size")),
                SettingsChild("Max image cache size", "max_image_cache_size", listOf("max image cache", "image cache size", "thumbnail cache size")),
                SettingsChild("Max canvas cache size", "max_cache_size", listOf("max canvas cache", "canvas cache size", "motion artwork cache size")),
                SettingsChild("Clear lyrics cache", "clear_lyrics_cache", listOf("clear lyrics cache", "delete lyrics cache", "wipe lyrics cache")),
                SettingsChild("Choose folder", "storage_folder_pick", listOf("choose folder", "pick folder", "storage folder", "select directory")),
                SettingsChild("Storage used", "size_used", listOf("storage used", "space used", "size used", "disk usage")),
            ),
        )
    val downloads =
        SettingsItem(
            key = "downloads",
            icon = R.drawable.download,
            title = context.getString(R.string.downloads),
            subtitle = context.getString(R.string.settings_downloads_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("download", "downloader", "external downloader", "download source", "auto download", "export songs", "clear downloads", "offline"),
            onClick = { navController.navigate("settings/downloads") },
            children = listOf(
                SettingsChild("Clear all downloads", "clear_all_downloads", listOf("clear downloads", "delete downloads", "remove downloads")),
                SettingsChild("Export downloaded songs", "export_downloaded_songs", listOf("export", "export songs", "save songs", "local storage", "file")),
                SettingsChild("Auto download on like", "auto_download_like", listOf("auto download", "like", "download liked")) { SearchResultSwitch(AutoDownloadOnLikeKey, false) },
                SettingsChild("Download source", "download_source", listOf("download source", "qobuz download", "tidal download", "youtube music download")),
                SettingsChild("External downloader", "external_downloader", listOf("external downloader", "download app", "custom downloader")) { SearchResultSwitch(ExternalDownloaderEnabledKey, false) },
                SettingsChild("External downloader package", "external_downloader_package", listOf("downloader package", "downloader app name")),
            ),
        )
    val backupRestore =
        SettingsItem(
            key = "backup_restore",
            icon = R.drawable.backup,
            title = context.getString(R.string.backup_restore),
            subtitle = context.getString(R.string.settings_backup_restore_subtitle),
            accentColor = colorScheme.primary,
            keywords = listOf("backup", "restore", "export", "import", "data", "save", "scheduled", "playlist", "csv", "m3u"),
            onClick = { navController.navigate("settings/backup_restore") },
            children = listOf(
                SettingsChild("Scheduled backup", "scheduled_backup", listOf("scheduled backup", "auto backup", "schedule", "automatic backup", "backup schedule", "periodic backup")),
                SettingsChild("Scheduled backup frequency", "scheduled_backup_frequency", listOf("backup frequency", "schedule frequency", "backup interval")),
                SettingsChild("Scheduled backup directory", "scheduled_backup_directory", listOf("backup directory", "backup folder", "backup location")),
                SettingsChild("Scheduled backup overwrite", "scheduled_backup_overwrite", listOf("overwrite backup", "replace backup")),
                SettingsChild("Backup", "backup", listOf("backup", "save data", "export backup")),
                SettingsChild("Restore", "restore", listOf("restore", "import", "recover")),
                SettingsChild("Import online (m3u)", "import_online", listOf("import online", "m3u", "playlist import")),
                SettingsChild("Import CSV", "import_csv", listOf("import csv", "csv", "playlist csv")),
                SettingsChild("Enable scheduled backup", "scheduled_backup_enabled", listOf("enable scheduled backup", "auto backup", "automatic backup", "periodic backup")),
                SettingsChild("Overwrite existing backup", "scheduled_backup_overwrite", listOf("overwrite backup", "replace backup", "overwrite existing")),
                SettingsChild("Enable cloud sync", "google_drive_sync_enabled", listOf("cloud sync", "google drive", "drive sync", "enable cloud sync", "gdrive", "backup to drive")),
                SettingsChild("Drive folder", "google_drive_sync_remote_folder", listOf("drive folder", "google drive folder", "remote folder", "cloud folder")),
                SettingsChild("Clear Drive folder", "google_drive_sync_clear_folder", listOf("clear drive folder", "empty drive folder", "delete drive backups")),
                SettingsChild("Overwrite existing Drive backup", "google_drive_sync_overwrite", listOf("overwrite drive backup", "replace drive backup", "drive overwrite")),
                SettingsChild("Drive sync frequency", "google_drive_sync_frequency", listOf("drive sync frequency", "cloud sync frequency", "sync interval", "sync schedule")),
                SettingsChild("Sync now", "google_drive_sync_run_now", listOf("sync now", "run sync", "backup now", "upload now")),
            ),
        )
    val developerOptions =
        SettingsItem(
            key = "developer_options",
            icon = R.drawable.experiment,
            title = context.getString(R.string.settings_developer_options_title),
            subtitle = context.getString(R.string.settings_developer_options_subtitle),
            accentColor = colorScheme.tertiary,
            keywords = listOf("developer", "debug", "experimental", "advanced", "logcat", "dev", "changelog", "update"),
            onClick = { navController.navigate("settings/misc") },
            children = listOf(
                SettingsChild("Logcat", "logcat", listOf("logcat", "log", "debug log")),
                SettingsChild("Changelog", "changelog", listOf("changelog", "changes", "release notes", "what's new")),
                SettingsChild("Update channel", "update_channel", listOf("update channel", "nightly", "stable", "beta")),
                SettingsChild("Enable update notification", "enable_update_notification", listOf("update notification", "notify update", "update alert")),
                SettingsChild("YTM sync", "ytm_sync", listOf("ytm sync", "youtube music sync", "sync library")),
                SettingsChild("Force sync on account switch", "force_sync_account_switch", listOf("force sync", "account switch sync", "sync on switch")),
                SettingsChild("Show nerd stats", "show_nerd_stats", listOf("nerd stats", "show nerd stats", "debug stats", "playback stats", "technical info")),
                SettingsChild("Show Discord debug UI", "show_discord_debug_ui", listOf("discord debug", "show debug ui", "discord debug ui")),
                SettingsChild("Debug logs", "debug_logs", listOf("debug logs", "verbose logs", "diagnostic logs")),
                SettingsChild(
                    context.getString(R.string.crash_logs_title),
                    "crash_logs",
                    listOf("crash logs", "crash", "crash report", "crash history", "anr", "native crash", "tombstone", "exit reason", "app exit"),
                ),
                SettingsChild(
                    context.getString(R.string.acoustic_quality_title),
                    "acoustic_quality",
                    listOf("acoustic", "quality graph", "spectrogram", "spectrum", "cutoff", "analysis"),
                ),
            ),
        )
    val defaultLinks =
        if (isAndroid12OrLater) {
            SettingsItem(
                key = "default_links",
                icon = R.drawable.link,
                title = context.getString(R.string.default_links),
                subtitle = context.getString(R.string.open_supported_links),
                accentColor = colorScheme.secondary,
                keywords = listOf("default links", "links", "urls", "deep link", "supported links"),
                onClick = {
                    try {
                        val intent =
                            Intent(
                                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        when (e) {
                            is ActivityNotFoundException,
                            is SecurityException,
                            -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }

                            else -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
                // Behaviour sub-page anchor, kept in the search index so existing search shortcuts
                // still work.
                hidden = true,
            )
        } else {
            null
        }
    val updates =
        if (BuildConfig.UPDATER_AVAILABLE) {
            SettingsItem(
                key = "updates",
                icon = R.drawable.ic_refresh,
                title = context.getString(R.string.updates),
                keywords = listOf("update", "upgrade", "version", "new version", "release", "nightly", "stable"),
                subtitle =
                    if (hasUpdate) {
                        context.getString(R.string.new_version_available)
                    } else {
                        context.getString(R.string.settings_updates_subtitle)
                    },
                showUpdateIndicator = hasUpdate,
                accentColor =
                    if (hasUpdate) {
                        colorScheme.tertiary
                    } else {
                        colorScheme.primary
                    },
                badge = if (hasUpdate) "v${BuildConfig.VERSION_NAME}" else BuildConfig.VERSION_NAME,
                onClick = { navController.navigate("settings/update") },
            )
        } else {
            null
        }
    val about =
        SettingsItem(
            key = "about",
            icon = R.drawable.ic_about,
            title = context.getString(R.string.about),
            subtitle = context.getString(R.string.settings_about_subtitle),
            accentColor = colorScheme.secondary,
            keywords = listOf("about", "info", "version", "license", "credits", "contributors", "changelog"),
            onClick = { navController.navigate("settings/about") },
            children = listOf(
                SettingsChild(context.getString(R.string.about_version), "about_version", listOf("version", "build")),
                SettingsChild(context.getString(R.string.changelog), "about_changelog", listOf("changelog", "changes", "release notes", "what's new")),
                SettingsChild(context.getString(R.string.about_open_source_licenses), "about_license", listOf("license", "gpl", "open source")),
            ),
        )

    return listOf(
        SettingsGroup(
            title = context.getString(R.string.settings),
            items = listOf(account, stats, listenTogether),
        ),
        SettingsGroup(
            title = context.getString(R.string.settings_section_player_content),
            items =
                listOf(
                    appearance,
                    appearanceExtras,
                    aodCustomization,
                    navigationBar, liquidGlassSettingsItem(context, navController, colorScheme),
                    lyricsAnimations,
                    playback,
                    canvas,
                    sources,
                    modules,
                    jioSaavn,
                    amazon,
                    qqMusic,
                    spotifyStreaming,
                    deezer,
                    lyrics,
                    lyricsProviders,
                    lyricsRomanisation,
                    content,
                    behavior,
                ),
        ),
        SettingsGroup(
            title = context.getString(R.string.integration),
            // Discord / Last.fm / Tidal / Qobuz / Telegram are intentionally NOT
            // top-level items here — they live as children of `integration` (and
            // also under their respective `sources` / `integration` screens).
            // Separate rows on the main settings page are redundant noise. The
            // `hidden = true` entries below exist purely so their own sub-page
            // settings are searchable.
            items =
                listOf(
                    androidAuto,
                    integration,
                    aiIntegration,
                    discordExperimental,
                    tidalDetail,
                    qobuzDetail,
                    telegramDetail,
                    internet,
                    poToken,
                ),
        ),
        SettingsGroup(
            title = context.getString(R.string.storage),
            items = listOf(storage, downloads, languagePacks, backupRestore),
        ),
        SettingsGroup(
            title = context.getString(R.string.about),
            items =
                buildList {
                    add(developerOptions)
                    defaultLinks?.let(::add)
                    updates?.let(::add)
                    add(about)
                },
        ),
    )
}
