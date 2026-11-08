package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*

/**
 * Pins the desktop settings tree: its groups, the routes its entries reach, the developer pages
 * that only appear while searching, and the rows each desktop page leaves to the phone hosts.
 */
class DesktopSettingsNavTest {
    private fun navRows() =
        desktopSettingsNavRows(
            navigate = {},
            openIntegrations = {},
            openEqualizer = {},
            openShortcuts = {},
        )

    @Test
    fun groupsAreDrawnInMetrolistOrder() {
        assertEquals(
            listOf(
                "desktop_group_playback",
                "desktop_group_interface",
                "desktop_group_integrations",
                "desktop_group_privacy",
                "desktop_group_storage",
                "desktop_group_application",
            ),
            navRows().filterIsInstance<SettingsRow.Header>().map { it.anchor },
        )
    }

    @Test
    fun everyNavigationEntryNamesASettingsRoute() {
        val routes = navRows().filterIsInstance<SettingsRow.Action>().mapNotNull { it.navRoute }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.startsWith("settings/") || it == "stats" })
        // Every page this host mounts is reachable from the tree.
        listOf(
                "settings/player",
                DesktopQueueRoute,
                DesktopEqualizerRoute,
                DesktopShortcutsRoute,
                "settings/sources",
                "settings/lyrics",
                "settings/lyrics/providers",
                "settings/lyrics/romanisation",
                "settings/ai_integration",
                "settings/po_token",
                "settings/appearance",
                "settings/content",
                "settings/canvas",
                "settings/language_packs",
                "settings/integration",
                "settings/tidal",
                "settings/qobuz",
                "settings/deezer",
                "settings/spotify_streaming",
                "settings/qqmusic",
                "settings/jiosaavn",
                "settings/amazon",
                "settings/applemusic",
                "settings/telegram",
                "settings/internet",
                "settings/account",
                "settings/privacy",
                "settings/crash_logs",
                "settings/storage",
                "settings/downloads",
                "settings/backup_restore",
                "settings/update",
                "settings/changelog",
                "settings/about",
                "settings/misc",
                "settings/logcat",
                "settings/appearance/lyrics_animations",
                DesktopIntegrationsRoute,
                DesktopConnectedDevicesRoute,
                "settings/integrations/device_connect",
            )
            .forEach { route -> assertTrue(route in routes, "missing tree entry for $route") }
    }

    @Test
    fun developerPagesAreHiddenUntilTheUserSearches() {
        val actions = navRows().filterIsInstance<SettingsRow.Action>()
        assertEquals(
            setOf("settings/misc", "settings/logcat"),
            actions.filter { it.hidden }.mapNotNull { it.navRoute }.toSet(),
        )
        val listed =
            desktopSettingsNavListRows({}, {}, {}, {}).filterIsInstance<SettingsRow.Action>()
        assertTrue(listed.none { it.hidden })
    }

    @Test
    fun connectedServicesOpensTheOverlayInsteadOfReplacingThePage() {
        val row =
            navRows().filterIsInstance<SettingsRow.Action>().single {
                it.anchor == "connected_services"
            }
        assertEquals(DesktopIntegrationsRoute, row.navRoute)
        val destinations = desktopIntegrationDestinations().map { it.route }
        assertEquals(
            listOf(
                "settings/discord",
                "settings/lastfm",
                "settings/integrations/listen_together",
                "settings/integrations/device_connect",
                "settings/telegram",
                "settings/spotify_streaming",
            ),
            destinations,
        )
    }

    @Test
    fun theQueuePageOwnsTheQueueRowsAndPlaybackLinksToIt() {
        val desktopPlayback =
            playbackSettingsRows(
                PreferenceSnapshot.EMPTY,
                false,
                false,
                false,
                {},
                {},
                {},
                desktop = true,
            )
        assertTrue(desktopPlayback.any { it.anchor == "queue_page" })
        listOf(
                "persistent_queue",
                "infinite_queue",
                "permanent_shuffle",
                "queue_after_manual",
                "swipe_up_queue",
            )
            .forEach {
                assertTrue(
                    desktopPlayback.none { row -> row.anchor == it },
                    "desktop playback kept $it",
                )
            }
        val queue = desktopQueueSettingsRows().map { it.anchor }
        assertEquals(
            listOf(
                "persistent_queue",
                "infinite_queue",
                "permanent_shuffle",
                "queue_after_manual",
                "prevent_duplicates",
            ),
            queue,
        )
    }

    @Test
    fun phoneOnlyRowsStayOffTheDesktopPages() {
        val playback =
            playbackSettingsRows(
                PreferenceSnapshot.EMPTY,
                false,
                false,
                false,
                {},
                {},
                {},
                desktop = true,
            )
        listOf(
                "enable_pip_mode",
                "wakelock",
                "stop_task_clear",
                "bluetooth_auto_start",
                "bit_perfect_usb",
                "usb_exclusive_audio",
                "audio_offload",
                "reject_suspected_upscaled_audio",
                "history_duration",
            )
            .forEach { anchor ->
                assertTrue(playback.none { it.anchor == anchor }, "desktop playback kept $anchor")
            }

        val theme =
            appearanceThemeSettingsRows(
                PreferenceSnapshot.EMPTY,
                false,
                false,
                false,
                false,
                {},
                {},
                {},
                {},
                {},
                desktop = true,
            )
        listOf(
                "splash_overlay_enabled",
                "hide_status_bar",
                "ui_scale",
                "app_icon",
                "palette_picker",
                "apple_music_palette",
                "blur_intensity",
                "backdrop_blur",
                "backdrop_blur_amount",
            )
            .forEach { anchor ->
                assertTrue(theme.none { it.anchor == anchor }, "desktop appearance kept $anchor")
            }

        val player =
            playerAppearanceSettingsRows(PreferenceSnapshot.EMPTY, true, {}, {}, desktop = true)
        listOf(
                "aod_customize",
                "compact_mini_player",
                "disable_animated_artwork_on_mobile_data",
                "library_style",
                "mini_player_background_style",
                "crop_thumbnail_to_square",
                "album_canvas_enabled",
            )
            .forEach { anchor ->
                assertTrue(
                    player.none { it.anchor == anchor },
                    "desktop player appearance kept $anchor",
                )
            }

        val interfaceRows = appearanceInterfaceSettingsRows({}, {}, {}, desktop = true)
        listOf(
                "home_screen_style",
                "minimal_home_mode",
                "hide_scrollbar",
                "navigation_bar_settings",
                "tablet_mode",
                "search_bar_position",
            )
            .forEach { anchor ->
                assertTrue(
                    interfaceRows.none { it.anchor == anchor },
                    "desktop interface kept $anchor",
                )
            }
    }

    @Test
    fun appearanceKeepsTheDesktopWindowChoices() {
        val rows =
            appearanceSettingsRows(
                PreferenceSnapshot.EMPTY,
                false,
                false,
                false,
                false,
                false,
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                desktop = true,
            )
        assertEquals(
            DesktopSystemWindowFrameKey,
            (rows.single { it.anchor == "system_window_frame" } as SettingsRow.Toggle).key,
        )
        assertEquals(
            DesktopHideToTrayKey,
            (rows.single { it.anchor == "close_to_tray" } as SettingsRow.Toggle).key,
        )
        // The phone pages keep the same rows they had: the desktop choices are additive.
        val phoneRows =
            appearanceSettingsRows(
                PreferenceSnapshot.EMPTY,
                false,
                false,
                false,
                false,
                false,
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
            )
        assertTrue(
            phoneRows.none { it.anchor == "system_window_frame" || it.anchor == "close_to_tray" },
        )
    }

    @Test
    fun privacyOwnsTheHistoryChoicesAndTheDuration() {
        assertEquals(
            listOf(
                "pause_listen_history",
                "pause_search_history",
                "history_duration",
                "sync_yt_history",
            ),
            desktopPrivacySettingsRows().map { it.anchor },
        )
    }

    @Test
    fun connectedDevicesIsADiscoverableCommandThatDoesNotNavigate() {
        var opened = 0
        val rows = desktopSettingsNavRows(
            navigate = {},
            openIntegrations = {},
            openEqualizer = {},
            openShortcuts = {},
            openConnectedDevices = { opened++ },
        )
        val row = rows.filterIsInstance<SettingsRow.Action>().single { it.anchor == "connected_devices" }
        assertEquals(DesktopConnectedDevicesRoute, row.navRoute)
        // A command row is listed (it has a route) but its tap opens the sheet, not a settings page.
        row.run()
        assertEquals(1, opened)
    }

    @Test
    fun deviceConnectManagementIsAFirstClassIntegrationsEntry() {
        val navigated = mutableListOf<String>()
        val rows = desktopSettingsNavRows(
            navigate = { navigated += it },
            openIntegrations = {},
            openEqualizer = {},
            openShortcuts = {},
        )
        val row = rows.filterIsInstance<SettingsRow.Action>().single { it.anchor == "device_connect" }
        assertEquals("settings/integrations/device_connect", row.navRoute)
        row.run()
        assertEquals(listOf("settings/integrations/device_connect"), navigated)
        // It is a plain top-level entry, not a developer row hidden until search.
        assertFalse(row.hidden)
    }

    @Test
    fun listRowsStillCarryTheConnectedDevicesCommand() {
        var opened = 0
        val rows = desktopSettingsNavListRows(
            navigate = {},
            openIntegrations = {},
            openEqualizer = {},
            openShortcuts = {},
            openConnectedDevices = { opened++ },
        )
        val row = rows.filterIsInstance<SettingsRow.Action>().single { it.anchor == "connected_devices" }
        row.run()
        assertEquals(1, opened)
    }
}
