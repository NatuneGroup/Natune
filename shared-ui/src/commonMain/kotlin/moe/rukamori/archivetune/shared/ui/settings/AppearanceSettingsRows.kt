package moe.rukamori.archivetune.shared.ui.settings

import kotlin.math.roundToInt
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

fun appearanceThemeSettingsRows(
    preferences: PreferenceSnapshot,
    systemDark: Boolean,
    lowRam: Boolean,
    nativeDynamicColors: Boolean,
    wallpaperPermissionNeeded: Boolean,
    navigate: (String) -> Unit,
    openWallpaperSettings: () -> Unit,
    chooseInterface: () -> Unit,
    chooseFont: () -> Unit,
    pickCustomFont: () -> Unit,
    customFontPickerSupported: Boolean = true,
    nativeIosInterface: Boolean = false,
    /**
     * Desktop has one fixed shell and no phone palette experiment, so the rows below stay off its
     * appearance page.
     */
    desktop: Boolean = false,
): List<SettingsRow> = buildList {
    val blur = !preferences[DisableBlurKey, false]
    val dynamic = preferences[DynamicThemeKey, true]
    if (!nativeIosInterface) {
        // The iOS host draws no refraction, so its appearance page shows neither glass toggle; the
        // stored glass
        // choices stay untouched for the other hosts. Desktop shows no phone navigation bar to
        // tint.
        add(
            SettingsRow.Toggle(
                "liquid_glass_effects",
                Res.string.m6_liquid_glass,
                LiquidGlassEnabledKey,
                false,
                enabled = blur,
            ),
        )
        if (!desktop) {
            add(
                SettingsRow.Action(
                    "liquid_glass_nav_bar_link",
                    Res.string.m6_nav_glass,
                    {
                        navigate("settings/appearance/navigation_bar?scrollTo=liquid_glass_nav_bar")
                    },
                ),
            )
        }
    }
    add(
        SettingsRow.Action(
            "interface_style",
            Res.string.m6_interface_style,
            chooseInterface,
            description =
                when (preferences.interfaceStyle()) {
                    InterfaceStyle.MATERIAL_EXPRESSIVE -> Res.string.m6_material_expressive
                    InterfaceStyle.APPLE_MUSIC -> Res.string.m6_apple_music
                    InterfaceStyle.CUSTOM -> Res.string.m6_interface_custom_mixed
                },
        ),
    )
    add(
        SettingsRow.Toggle(
            "apple_music_menus",
            Res.string.m6_apple_music_menus,
            AppleMusicMenusKey,
            false,
            description = Res.string.m6_apple_music_independent_desc,
            read = { it.appleMusicMenus() },
        ),
    )
    add(
        SettingsRow.Toggle(
            "apple_music_search_bar",
            Res.string.m6_apple_music_search_bar,
            AppleMusicSearchBarKey,
            false,
            description = Res.string.m6_apple_music_independent_desc,
            read = { it.appleMusicSearchBar() },
        ),
    )
    // The Apple Music palette, the icon pack and the follow-system random palette have no desktop
    // reader; the rows
    // would only write a preference nothing draws, so the desktop page leaves them out.
    if (!desktop) {
        add(
            SettingsRow.Toggle(
                "apple_music_palette",
                Res.string.m6_apple_music_palette,
                AppleMusicPaletteKey,
                false,
                description = Res.string.m6_apple_music_palette_desc,
                read = { it.appleMusicPalette() },
            ),
        )
    }
    add(
        SettingsRow.Toggle(
            "dynamic_theme",
            Res.string.m6_dynamic_theme,
            DynamicThemeKey,
            true,
            description = Res.string.m6_palette_sources_exclusive,
            write = { setDynamicTheme(it) },
        ),
    )
    if (dynamic && !nativeDynamicColors && wallpaperPermissionNeeded)
        add(
            SettingsRow.Action(
                "wallpaper_permission",
                Res.string.m6_wallpaper_permission,
                openWallpaperSettings,
            ),
        )
    if (!desktop) {
        if (!dynamic)
            add(
                SettingsRow.Toggle(
                    "random_theme_on_startup",
                    Res.string.m6_random_theme,
                    RandomThemeOnStartupKey,
                    false,
                    description = Res.string.m6_palette_sources_exclusive,
                    write = { setRandomThemeOnStartup(it) },
                ),
            )
        if (!dynamic || !nativeDynamicColors)
            add(
                SettingsRow.Action(
                    "palette_picker",
                    Res.string.m6_palette,
                    { navigate("settings/appearance/palette_picker") },
                ),
            )
    }
    // The style shapes the saved palette color, which applies only while dynamic theme is off.
    if (!dynamic)
        add(
            SettingsRow.Choice(
                "theme_palette_style",
                Res.string.m6_theme_palette_style,
                ThemePaletteStyleKey,
                ThemePaletteStyle.AUTOMATIC.name,
                listOf(
                    SettingsRow.Option(
                        ThemePaletteStyle.AUTOMATIC.name,
                        Res.string.m6_palette_style_automatic,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.TONAL_SPOT.name,
                        Res.string.m6_palette_style_tonal_spot,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.VIBRANT.name,
                        Res.string.m6_palette_style_vibrant,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.EXPRESSIVE.name,
                        Res.string.m6_palette_style_expressive,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.RAINBOW.name,
                        Res.string.m6_palette_style_rainbow,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.FRUIT_SALAD.name,
                        Res.string.m6_palette_style_fruit_salad,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.MONOCHROME.name,
                        Res.string.m6_palette_style_monochrome,
                    ),
                    SettingsRow.Option(
                        ThemePaletteStyle.NEUTRAL.name,
                        Res.string.m6_palette_style_neutral,
                    ),
                ),
                description = Res.string.m6_palette_style_desc,
            ),
        )
    if (!desktop)
        add(
            SettingsRow.Action(
                "app_icon",
                Res.string.m6_app_icon,
                { navigate("settings/appearance/icon") },
            ),
        )
    add(
        SettingsRow.Choice(
            "dark_theme",
            Res.string.m6_dark_theme,
            DarkModeKey,
            "AUTO",
            listOf(
                SettingsRow.Option("ON", Res.string.m6_on),
                SettingsRow.Option("OFF", Res.string.m6_off),
                SettingsRow.Option("AUTO", Res.string.m6_follow_system),
            ),
        ),
    )
    val dark =
        when (preferences[DarkModeKey, "AUTO"]) {
            "ON" -> true
            "OFF" -> false
            else -> systemDark
        }
    if (dark) add(SettingsRow.Toggle("pure_black", Res.string.m6_pure_black, PureBlackKey, false))
    add(SettingsRow.Toggle("disable_blur", Res.string.m6_disable_blur, DisableBlurKey, false))
    add(
        SettingsRow.Toggle(
            "disable_animations",
            Res.string.m6_disable_animations,
            DisableAnimationsKey,
            lowRam,
        ),
    )
    // The splash, the system status bar and the phone density scale are Android surfaces; the
    // desktop window draws its
    // own scale at the system setting.
    if (!desktop) {
        add(
            SettingsRow.Toggle(
                "splash_overlay_enabled",
                Res.string.m6_splash,
                SplashOverlayEnabledKey,
                true,
            ),
        )
        add(
            SettingsRow.Toggle(
                "hide_status_bar",
                Res.string.m6_hide_status_bar,
                HideStatusBarKey,
                false,
            ),
        )
        add(
            SettingsRow.Decimal(
                "ui_scale",
                Res.string.m6_ui_scale,
                UiScaleFactorKey,
                1f,
                .85f..1.30f,
                44,
                normalize = { (it * 100).roundToInt() / 100f },
            ),
        )
    }
    // These three tune the phone player's blur backdrop; the desktop window has no such surface, so
    // the rows would
    // write keys nothing on this host reads. `disable_blur` stays: desktop glass reads it. Keys
    // stay stored for Android.
    if (!desktop) {
        add(
            SettingsRow.Decimal(
                "blur_intensity",
                Res.string.m6_blur_intensity,
                BlurRadiusKey,
                48f,
                0f..64f,
                63,
                enabled = blur,
            ),
        )
        add(
            SettingsRow.Toggle(
                "backdrop_blur",
                Res.string.m6_backdrop,
                BackdropEnabledKey,
                true,
                enabled = blur,
            ),
        )
        add(
            SettingsRow.Number(
                "backdrop_blur_amount",
                Res.string.m6_backdrop_amount,
                BackdropBlurAmountKey,
                60,
                0..100,
                enabled = blur && preferences[BackdropEnabledKey, true],
            ),
        )
    }
    add(SettingsRow.Action("font_preference", Res.string.m6_font, chooseFont))
    val font =
        RestartScopedSettings.resolveFontPreference(
            preferences[FontPreferenceKey],
            preferences[UseSystemFontKey],
        )
    if (font == AppFontPreference.CUSTOM)
        add(
            SettingsRow.Action(
                "custom_font",
                Res.string.m6_custom_font,
                pickCustomFont,
                enabled = customFontPickerSupported,
                description =
                    Res.string.m6_custom_font_picker_unavailable.takeUnless {
                        customFontPickerSupported
                    },
            ),
        )
}

fun appearanceInterfaceSettingsRows(
    navigate: (String) -> Unit,
    arrangeLibraryChips: () -> Unit,
    arrangePlaylistTags: () -> Unit,
    /**
     * Desktop keeps one fixed shell: the top bar owns search and the rail owns navigation, so the
     * phone navigation style, search position, tablet switch and back animation are not offered.
     * Their stored keys stay untouched.
     */
    desktop: Boolean = false,
): List<SettingsRow> = buildList {
    // The desktop home is the shell's page and its scrollbars belong to the window, so the phone
    // home style, the
    // minimal home switch and the scrollbar switch have no desktop surface.
    if (!desktop) {
        add(
            SettingsRow.Choice(
                "home_screen_style",
                Res.string.m6_home_style,
                HomeScreenStyleKey,
                "DEFAULT",
                listOf(
                    SettingsRow.Option("DEFAULT", Res.string.m6_default),
                    SettingsRow.Option("RUKAMORI", Res.string.m6_rukamori),
                ),
            ),
        )
        add(
            SettingsRow.Toggle(
                "minimal_home_mode",
                Res.string.m6_minimal_home,
                MinimalHomeModeKey,
                false,
            ),
        )
    }
    add(
        SettingsRow.Choice(
            "default_open_tab",
            Res.string.m6_open_tab,
            DefaultOpenTabKey,
            "HOME",
            listOf(
                SettingsRow.Option("HOME", Res.string.m6_home),
                SettingsRow.Option("SEARCH", Res.string.m6_search),
                SettingsRow.Option("LIBRARY", Res.string.m6_library),
            ),
        ),
    )
    if (!desktop) {
        add(
            SettingsRow.Choice(
                "search_bar_position",
                Res.string.m6_search_position,
                SearchBarPositionKey,
                "TOP",
                listOf(
                    SettingsRow.Option("TOP", Res.string.m6_top),
                    SettingsRow.Option("BOTTOM", Res.string.m6_bottom),
                ),
            ),
        )
        add(
            SettingsRow.Toggle(
                "hide_search_bar_while_scrolling",
                Res.string.m6_hide_search,
                HideSearchChromeWhileScrollingKey,
                false,
            ),
        )
        add(
            SettingsRow.Choice(
                "back_animation_style",
                Res.string.m6_back_animation,
                BackAnimationStyleKey,
                "PREDICTIVE",
                listOf(
                    SettingsRow.Option("ORIGINAL", Res.string.m6_original),
                    SettingsRow.Option("PREDICTIVE", Res.string.m6_predictive),
                ),
            ),
        )
        add(SettingsRow.Toggle("tablet_mode", Res.string.m6_tablet, TabletModeEnabledKey, false))
        add(
            SettingsRow.Action(
                "navigation_bar_settings",
                Res.string.m6_navigation_bar,
                { navigate("settings/appearance/navigation_bar") },
            ),
        )
        add(
            SettingsRow.Toggle(
                "hide_scrollbar",
                Res.string.m6_hide_scrollbar,
                HideScrollbarKey,
                false,
            ),
        )
    }
    add(
        SettingsRow.Choice(
            "default_lib_chips",
            Res.string.m6_default_chip,
            ChipSortTypeKey,
            "LIBRARY",
            listOf(
                SettingsRow.Option("LIBRARY", Res.string.m6_library),
                SettingsRow.Option("PLAYLISTS", Res.string.m6_playlists),
                SettingsRow.Option("SONGS", Res.string.m6_songs),
                SettingsRow.Option("ALBUMS", Res.string.m6_albums),
                SettingsRow.Option("ARTISTS", Res.string.m6_artists),
            ),
        ),
    )
    add(
        SettingsRow.Action(
            "arrange_library_chips",
            Res.string.m6_arrange_chips,
            arrangeLibraryChips,
        ),
    )
    add(
        SettingsRow.Action(
            "arrange_playlist_tags",
            Res.string.m6_arrange_tags,
            arrangePlaylistTags,
        ),
    )
    add(
        SettingsRow.Action(
            "extras",
            Res.string.m6_extras,
            { navigate("settings/appearance/extras") },
        ),
    )
}

fun appearanceExtrasSettingsRows(): List<SettingsRow> =
    listOf(
        SettingsRow.Toggle(
            "show_home_category_chips",
            Res.string.m6_home_categories,
            ShowHomeCategoryChipsKey,
            false,
        ),
        SettingsRow.Toggle(
            "show_tags_in_library",
            Res.string.m6_library_tags,
            ShowTagsInLibraryKey,
            false,
        ),
        SettingsRow.Toggle(
            "hide_liked_songs_card",
            Res.string.m6_hide_liked,
            HideLikedSongsCardKey,
            false,
        ),
        SettingsRow.Toggle(
            "hide_offline_card",
            Res.string.m6_hide_offline,
            HideOfflineCardKey,
            false,
        ),
        SettingsRow.Toggle("hide_cached_card", Res.string.m6_hide_cached, HideCachedCardKey, false),
        SettingsRow.Toggle(
            "hide_local_files_card",
            Res.string.m6_hide_local,
            HideLocalFilesCardKey,
            false,
        ),
        SettingsRow.Toggle("hide_top50_card", Res.string.m6_hide_top50, HideTop50CardKey, false),
    )
