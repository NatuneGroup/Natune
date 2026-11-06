package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The top settings page's rows, in the Android settings order. The page renders them as one scrolling list; the desktop
 * two-pane shell walks the same rows for its navigation pane, so a category cannot exist in one place and not the other.
 * Rows that navigate carry their target in [SettingsRow.Action.navRoute].
 */
fun settingsHomeRows(
    navigate: (String) -> Unit,
    androidAuto: Boolean,
    hideToTrayKey: BooleanPreferenceKey?,
    openShortcuts: () -> Unit,
    /** Desktop keeps the Metrolist shell, so the phone navigation-style page is not offered; its keys stay stored. */
    desktop: Boolean = false,
): List<SettingsRow> {
    fun entry(anchor: String, title: StringResource, description: StringResource, route: String) =
        SettingsRow.Action(anchor, title, run = { navigate(route) }, description = description, navRoute = route)
    return buildList {
        add(SettingsRow.Header("group_settings", Res.string.m6_settings_home_group_settings))
        add(entry("account", Res.string.m6_settings_home_account, Res.string.m6_settings_home_account_desc, "settings/account"))
        add(entry("stats", Res.string.m6_settings_home_stats, Res.string.m6_settings_home_stats_desc, "stats"))
        add(entry("listen_together", Res.string.m6_settings_home_listen_together,
            Res.string.m6_settings_home_listen_together_desc, "listen_together"))
        add(SettingsRow.Header("group_player_content", Res.string.m6_settings_home_group_player_content))
        add(entry("appearance", Res.string.m6_settings_home_appearance, Res.string.m6_settings_home_appearance_desc,
            "settings/appearance"))
        add(entry("appearance_extras", Res.string.m6_settings_home_appearance_extras,
            Res.string.m6_settings_home_appearance_extras_desc, "settings/appearance/extras"))
        add(entry("aod", Res.string.m6_settings_home_aod, Res.string.m6_settings_home_aod_desc,
            "settings/appearance/aod_customized"))
        if (!desktop) {
            add(entry("navigation_bar", Res.string.m6_settings_home_navigation_bar,
                Res.string.m6_settings_home_navigation_bar_desc, "settings/appearance/navigation_bar"))
        }
        add(entry("lyrics_animations", Res.string.m6_settings_home_lyrics_animations,
            Res.string.m6_settings_home_lyrics_animations_desc, "settings/appearance/lyrics_animations"))
        add(entry("playback", Res.string.m6_settings_home_playback, Res.string.m6_settings_home_playback_desc, "settings/player"))
        add(entry("canvas", Res.string.m6_settings_home_canvas, Res.string.m6_settings_home_canvas_desc, "settings/canvas"))
        add(entry("sources", Res.string.m6_settings_home_sources, Res.string.m6_settings_home_sources_desc, "settings/sources"))
        add(entry("jiosaavn", Res.string.m6_settings_home_jiosaavn, Res.string.m6_settings_home_jiosaavn_desc, "settings/jiosaavn"))
        add(entry("amazon", Res.string.m6_settings_home_amazon, Res.string.m6_settings_home_amazon_desc, "settings/amazon"))
        add(entry("qqmusic", Res.string.m6_settings_home_qqmusic, Res.string.m6_settings_home_qqmusic_desc, "settings/qqmusic"))
        add(entry("spotify_streaming", Res.string.m6_settings_home_spotify_streaming,
            Res.string.m6_settings_home_spotify_streaming_desc, "settings/spotify_streaming"))
        add(entry("deezer", Res.string.m6_settings_home_deezer, Res.string.m6_settings_home_deezer_desc, "settings/deezer"))
        add(entry("lyrics", Res.string.m6_settings_home_lyrics, Res.string.m6_settings_home_lyrics_desc, "settings/lyrics"))
        add(entry("lyrics_providers", Res.string.m6_settings_home_lyrics_providers,
            Res.string.m6_settings_home_lyrics_providers_desc, "settings/lyrics/providers"))
        add(entry("lyrics_romanisation", Res.string.m6_settings_home_lyrics_romanisation,
            Res.string.m6_settings_home_lyrics_romanisation_desc, "settings/lyrics/romanisation"))
        add(entry("content", Res.string.m6_settings_home_content, Res.string.m6_settings_home_content_desc, "settings/content"))
        add(entry("behavior", Res.string.m6_settings_home_behavior, Res.string.m6_settings_home_behavior_desc, "settings/privacy"))
        add(SettingsRow.Header("group_integration", Res.string.m6_settings_home_group_integration))
        if (androidAuto) {
            add(entry("android_auto", Res.string.m6_settings_home_android_auto,
                Res.string.m6_settings_home_android_auto_desc, "settings/android_auto"))
        }
        add(entry("integration", Res.string.m6_settings_home_integration, Res.string.m6_settings_home_integration_desc,
            "settings/integration"))
        add(entry("ai_integration", Res.string.m6_settings_home_ai_integration,
            Res.string.m6_settings_home_ai_integration_desc, "settings/ai_integration"))
        add(entry("discord_experimental", Res.string.m6_settings_home_discord_experimental,
            Res.string.m6_settings_home_discord_experimental_desc, "settings/discord/experimental"))
        add(entry("internet", Res.string.m6_settings_home_internet, Res.string.m6_settings_home_internet_desc, "settings/internet"))
        add(entry("po_token", Res.string.m6_settings_home_po_token, Res.string.m6_settings_home_po_token_desc, "settings/po_token"))
        add(SettingsRow.Header("group_storage", Res.string.m6_settings_home_group_storage))
        add(entry("storage", Res.string.m6_settings_home_storage, Res.string.m6_settings_home_storage_desc, "settings/storage"))
        add(entry("downloads", Res.string.m6_settings_home_downloads, Res.string.m6_settings_home_downloads_desc,
            "settings/downloads"))
        add(entry("language_packs", Res.string.m6_settings_home_language_packs,
            Res.string.m6_settings_home_language_packs_desc, "settings/language_packs"))
        add(entry("backup_restore", Res.string.m6_settings_home_backup_restore,
            Res.string.m6_settings_home_backup_restore_desc, "settings/backup_restore"))
        if (hideToTrayKey != null) {
            add(SettingsRow.Header("group_window", Res.string.m6_settings_home_group_window))
            add(SettingsRow.Toggle(
                "close_to_tray", Res.string.m6_settings_home_close_to_tray, hideToTrayKey, false,
                description = Res.string.m6_settings_home_close_to_tray_desc,
            ))
        }
        // The shortcut table belongs next to the other reference entries, and it opens the same overlay the
        // Ctrl/Cmd + / key does, so the two can never disagree.
        add(SettingsRow.Header("shortcut_group", Res.string.shortcut_settings_group))
        add(
            SettingsRow.Action(
                "keyboard_shortcuts", Res.string.shortcut_settings_title,
                run = openShortcuts,
                description = Res.string.shortcut_settings_description,
            ),
        )
        add(SettingsRow.Header("group_about", Res.string.m6_settings_home_group_about))
        add(entry("developer_options", Res.string.m6_settings_home_developer_options,
            Res.string.m6_settings_home_developer_options_desc, "settings/misc"))
        add(entry("updates", Res.string.m6_settings_home_updates, Res.string.m6_settings_home_updates_desc, "settings/update"))
        add(entry("about", Res.string.m6_settings_home_about, Res.string.m6_settings_home_about_desc, "settings/about"))
    }
}
