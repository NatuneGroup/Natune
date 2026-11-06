package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The desktop settings tree, grouped the way Metrolist's desktop overlay groups it: Playback, Interface, Integrations,
 * Privacy & security, Storage & data, Application & about. Every entry is a page this host mounts, so a row can never
 * point at a surface the window does not have.
 *
 * The phone hosts keep [settingsHomeRows]; nothing here is shared with the Android single-column list.
 *
 * Rows whose reader is a page command rather than a route carry no [SettingsRow.Action.navRoute] and stay out of the
 * two-pane navigation, which lists routes only. [DesktopIntegrationsRoute] is the one command route: the window catches
 * it and opens the separate integrations overlay instead of replacing the page.
 */
const val DesktopIntegrationsRoute = "settings/integrations"

/** The desktop-only queue page the Playback group links to. */
const val DesktopQueueRoute = "settings/player/queue"

/**
 * The route-shaped row that opens the equalizer sheet. The window catches it and shows the sheet over the page, so the
 * row is listed and searchable in the navigation pane like a page.
 */
const val DesktopEqualizerRoute = "settings/player/equalizer"

/** The route-shaped row that opens the shortcut table, so it is listed and searchable instead of hidden in a page. */
const val DesktopShortcutsRoute = "settings/shortcuts"

/**
 * One row per group, in the order the settings overlay draws them. [openIntegrations], [openEqualizer] and
 * [openShortcuts] are commands the window owns; everything else navigates.
 *
 * Developer pages carry [SettingsRow.Action.hidden], so they only appear once the user searches for them, as Metrolist's
 * Devtools page does.
 */
fun desktopSettingsNavRows(
    navigate: (String) -> Unit,
    openIntegrations: () -> Unit,
    openEqualizer: () -> Unit,
    openShortcuts: () -> Unit,
): List<SettingsRow> {
    fun entry(anchor: String, title: StringResource, description: StringResource, route: String, hidden: Boolean = false) =
        SettingsRow.Action(
            anchor = anchor,
            title = title,
            run = { navigate(route) },
            description = description,
            navRoute = route,
            hidden = hidden,
        )
    return buildList {
        add(SettingsRow.Header("desktop_group_playback", Res.string.m6_settings_desktop_group_playback))
        add(
            entry(
                "player_and_audio", Res.string.m6_settings_desktop_player_audio,
                Res.string.m6_settings_desktop_player_audio_desc, "settings/player",
            ),
        )
        add(
            entry(
                "queue_page", Res.string.m6_settings_desktop_queue,
                Res.string.m6_settings_desktop_queue_desc, DesktopQueueRoute,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "equalizer_page",
                title = Res.string.m5pq_equalizer,
                run = openEqualizer,
                description = Res.string.m6_settings_desktop_equalizer_desc,
                navRoute = DesktopEqualizerRoute,
            ),
        )
        add(
            entry(
                "sources", Res.string.m6_settings_home_sources,
                Res.string.m6_settings_home_sources_desc, "settings/sources",
            ),
        )
        add(
            entry(
                "lyrics", Res.string.m6_settings_home_lyrics,
                Res.string.m6_settings_home_lyrics_desc, "settings/lyrics",
            ),
        )
        add(
            entry(
                "lyrics_providers", Res.string.m6_settings_home_lyrics_providers,
                Res.string.m6_settings_home_lyrics_providers_desc, "settings/lyrics/providers",
            ),
        )
        add(
            entry(
                "lyrics_romanisation", Res.string.m6_settings_home_lyrics_romanisation,
                Res.string.m6_settings_home_lyrics_romanisation_desc, "settings/lyrics/romanisation",
            ),
        )
        add(
            entry(
                "lyrics_animations", Res.string.m6_settings_home_lyrics_animations,
                Res.string.m6_settings_home_lyrics_animations_desc, "settings/appearance/lyrics_animations",
            ),
        )
        add(
            entry(
                "ai_lyrics", Res.string.m6_settings_desktop_ai_lyrics,
                Res.string.m6_settings_home_ai_integration_desc, "settings/ai_integration",
            ),
        )
        add(
            entry(
                "po_token", Res.string.m6_settings_home_po_token,
                Res.string.m6_settings_home_po_token_desc, "settings/po_token",
            ),
        )

        add(SettingsRow.Header("desktop_group_interface", Res.string.m6_settings_desktop_group_interface))
        add(
            entry(
                "appearance", Res.string.m6_settings_home_appearance,
                Res.string.m6_settings_home_appearance_desc, "settings/appearance",
            ),
        )
        add(
            entry(
                "content", Res.string.m6_settings_home_content,
                Res.string.m6_settings_desktop_content_desc, "settings/content",
            ),
        )
        add(
            entry(
                "canvas", Res.string.m6_settings_home_canvas,
                Res.string.m6_settings_home_canvas_desc, "settings/canvas",
            ),
        )
        add(
            entry(
                "language_packs", Res.string.m6_settings_home_language_packs,
                Res.string.m6_settings_home_language_packs_desc, "settings/language_packs",
            ),
        )

        add(SettingsRow.Header("desktop_group_integrations", Res.string.m6_settings_desktop_group_integrations))
        add(
            SettingsRow.Action(
                anchor = "connected_services",
                title = Res.string.m6_settings_desktop_connected_services,
                run = openIntegrations,
                description = Res.string.m6_settings_desktop_connected_services_desc,
                navRoute = DesktopIntegrationsRoute,
            ),
        )
        add(
            entry(
                "integration", Res.string.m6_settings_home_integration,
                Res.string.m6_settings_home_integration_desc, "settings/integration",
            ),
        )
        add(
            entry(
                "tidal", Res.string.m6_integration_tidal,
                Res.string.m6_integration_tidal_desc, "settings/tidal",
            ),
        )
        add(
            entry(
                "qobuz", Res.string.m6_integration_qobuz,
                Res.string.m6_integration_qobuz_desc, "settings/qobuz",
            ),
        )
        add(
            entry(
                "deezer", Res.string.m6_integration_deezer,
                Res.string.m6_integration_deezer_desc, "settings/deezer",
            ),
        )
        add(
            entry(
                "spotify_streaming", Res.string.m6_settings_home_spotify_streaming,
                Res.string.m6_settings_home_spotify_streaming_desc, "settings/spotify_streaming",
            ),
        )
        add(
            entry(
                "qqmusic", Res.string.m6_integration_qqmusic,
                Res.string.m6_integration_qqmusic_desc, "settings/qqmusic",
            ),
        )
        add(
            entry("jiosaavn", Res.string.m6_settings_home_jiosaavn, Res.string.m6_settings_home_jiosaavn_desc, "settings/jiosaavn"),
        )
        add(
            entry("amazon", Res.string.m6_integration_amazon, Res.string.m6_integration_amazon_desc, "settings/amazon"),
        )
        add(
            entry("applemusic", Res.string.m6_integration_applemusic, Res.string.m6_integration_applemusic_desc, "settings/applemusic"),
        )
        add(
            entry("telegram", Res.string.m6_integration_telegram, Res.string.m6_integration_telegram_desc, "settings/telegram"),
        )
        add(
            entry("internet", Res.string.m6_settings_home_internet, Res.string.m6_settings_home_internet_desc, "settings/internet"),
        )
        add(
            entry("account", Res.string.m6_settings_home_account, Res.string.m6_settings_home_account_desc, "settings/account"),
        )

        add(SettingsRow.Header("desktop_group_privacy", Res.string.m6_settings_desktop_group_privacy))
        add(
            entry(
                "privacy", Res.string.m6_settings_desktop_privacy,
                Res.string.m6_settings_desktop_privacy_desc, "settings/privacy",
            ),
        )
        add(
            entry(
                "crash_logs", Res.string.m6_settings_desktop_crash_reports,
                Res.string.m6_settings_desktop_crash_reports_desc, "settings/crash_logs",
            ),
        )

        add(SettingsRow.Header("desktop_group_storage", Res.string.m6_settings_desktop_group_storage))
        add(
            entry("storage", Res.string.m6_settings_home_storage, Res.string.m6_settings_home_storage_desc, "settings/storage"),
        )
        add(
            entry("downloads", Res.string.m6_settings_home_downloads, Res.string.m6_settings_home_downloads_desc, "settings/downloads"),
        )
        add(
            entry(
                "backup_restore", Res.string.m6_settings_desktop_backup_import,
                Res.string.m6_settings_desktop_backup_import_desc, "settings/backup_restore",
            ),
        )

        add(SettingsRow.Header("desktop_group_application", Res.string.m6_settings_desktop_group_application))
        add(
            entry("updates", Res.string.m6_settings_home_updates, Res.string.m6_settings_home_updates_desc, "settings/update"),
        )
        add(entry("changelog", Res.string.m6_settings_desktop_changelog, Res.string.m6_settings_desktop_changelog_desc, "settings/changelog"))
        add(entry("about", Res.string.m6_settings_home_about, Res.string.m6_settings_home_about_desc, "settings/about"))
        add(
            entry("stats", Res.string.m6_settings_home_stats, Res.string.m6_settings_home_stats_desc, "stats"),
        )
        add(
            entry(
                "developer_options", Res.string.m6_settings_desktop_devtools,
                Res.string.m6_settings_home_developer_options_desc, "settings/misc", hidden = true,
            ),
        )
        add(
            entry(
                "diagnostic_log", Res.string.m6_settings_desktop_diagnostic_log,
                Res.string.m6_settings_desktop_diagnostic_log_desc, "settings/logcat", hidden = true,
            ),
        )
        // The shortcut table opens the same overlay Ctrl/Cmd + / does, so the two can never disagree.
        add(
            SettingsRow.Action(
                anchor = "keyboard_shortcuts",
                title = Res.string.shortcut_settings_title,
                run = openShortcuts,
                description = Res.string.shortcut_settings_description,
                navRoute = DesktopShortcutsRoute,
            ),
        )
    }
}

/**
 * The same tree as a single-column list, for the window shapes that render rows instead of a navigation pane. Developer
 * entries are left out here: the pane reaches them through its search field, and a list has no search.
 */
fun desktopSettingsNavListRows(
    navigate: (String) -> Unit,
    openIntegrations: () -> Unit,
    openEqualizer: () -> Unit,
    openShortcuts: () -> Unit,
): List<SettingsRow> =
    desktopSettingsNavRows(navigate, openIntegrations, openEqualizer, openShortcuts)
        .filterNot { row -> row is SettingsRow.Action && row.hidden }

/**
 * One destination of the separate integrations overlay: the page it opens plus the strings that name and explain it.
 * The overlay and any direct page listing render this one list.
 */
data class IntegrationDestination(
    val route: String,
    val title: StringResource,
    val description: StringResource,
)

/**
 * The integrations this build actually has. ListenBrainz is a scrobbling provider inside the Last.fm page rather than a
 * page of its own, so the overlay lists the Last.fm / Libre.fm / ListenBrainz page once.
 */
fun desktopIntegrationDestinations(): List<IntegrationDestination> = listOf(
    IntegrationDestination(
        "settings/discord", Res.string.m6_settings_desktop_discord, Res.string.m6_settings_desktop_discord_desc,
    ),
    IntegrationDestination(
        "settings/lastfm", Res.string.m6_integration_lastfm, Res.string.m6_settings_home_scrobbling_desc,
    ),
    IntegrationDestination(
        "settings/integrations/listen_together", Res.string.m6_settings_home_listen_together,
        Res.string.m6_settings_home_listen_together_desc,
    ),
    IntegrationDestination(
        "settings/integrations/device_connect", Res.string.m9_device_connect_title,
        Res.string.m6_settings_home_device_connect_desc,
    ),
    IntegrationDestination("settings/telegram", Res.string.m6_integration_telegram, Res.string.m6_integration_telegram_desc),
    IntegrationDestination(
        "settings/spotify_streaming", Res.string.m6_settings_home_spotify_streaming,
        Res.string.m6_settings_home_spotify_streaming_desc,
    ),
)
