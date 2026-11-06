package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.resources.*

const val AppearanceRoute = "settings/appearance"

/**
 * The former Appearance sub-page routes. They stay mounted so old links and saved back stacks still open, and each
 * lands on the merged page at its section heading, whose anchor is the value here.
 */
val AppearanceSectionRoutes: Map<String, String> = mapOf(
    "settings/appearance/theme" to "appearance_theme",
    "settings/appearance/player" to "appearance_player",
    "settings/appearance/interface" to "appearance_interface",
)

/** The page anchor to open [path] at: an explicit [scrollTo] wins, a former sub-page route falls back to its section. */
fun appearanceScrollTarget(path: String, scrollTo: String?): String? = scrollTo ?: AppearanceSectionRoutes[path]

/** Theme, player and interface rows on one page, each section under its own heading. */
fun appearanceSettingsRows(
    preferences: PreferenceSnapshot,
    systemDark: Boolean,
    lowRam: Boolean,
    nativeDynamicColors: Boolean,
    wallpaperPermissionNeeded: Boolean,
    blurSupported: Boolean,
    navigate: (String) -> Unit,
    openWallpaperSettings: () -> Unit,
    chooseInterface: () -> Unit,
    chooseFont: () -> Unit,
    pickCustomFont: () -> Unit,
    chooseHomeScreens: () -> Unit,
    arrangeLibraryChips: () -> Unit,
    arrangePlaylistTags: () -> Unit,
    customFontPickerSupported: Boolean = true,
    nativeIosInterface: Boolean = false,
    desktop: Boolean = false,
): List<SettingsRow> = buildList {
    add(SettingsRow.Header("appearance_theme", Res.string.m6_appearance_theme))
    addAll(appearanceThemeSettingsRows(preferences, systemDark, lowRam, nativeDynamicColors, wallpaperPermissionNeeded,
        navigate, openWallpaperSettings, chooseInterface, chooseFont, pickCustomFont, customFontPickerSupported,
        nativeIosInterface))
    add(SettingsRow.Header("appearance_player", Res.string.m6_appearance_player))
    addAll(playerAppearanceSettingsRows(preferences, blurSupported, navigate, chooseHomeScreens))
    add(SettingsRow.Header("appearance_interface", Res.string.m6_appearance_interface))
    addAll(appearanceInterfaceSettingsRows(navigate, arrangeLibraryChips, arrangePlaylistTags, desktop))
}

/** settings/appearance: the one Appearance page. Platform effects and the home, chip and tag editors stay host actions. */
@Composable
fun AppearanceSettings(
    controller: SettingsController,
    systemDark: Boolean,
    lowRam: Boolean,
    nativeDynamicColors: Boolean,
    wallpaperPermissionNeeded: Boolean,
    blurSupported: Boolean,
    navigate: (String) -> Unit,
    openWallpaperSettings: () -> Unit,
    pickCustomFont: suspend () -> Pair<String, String>?,
    chooseHomeScreens: () -> Unit,
    arrangeLibraryChips: () -> Unit,
    arrangePlaylistTags: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
    customFontPickerSupported: Boolean = true,
    nativeIosInterface: Boolean = false,
) {
    val desktop = moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout.current
    ThemeChoicePage(controller, pickCustomFont, onFailure, scrollTo, modifier, customFontPickerSupported) { preferences, chooseInterface, chooseFont, pickFont ->
        appearanceSettingsRows(preferences, systemDark, lowRam, nativeDynamicColors, wallpaperPermissionNeeded, blurSupported,
            navigate, openWallpaperSettings, chooseInterface, chooseFont, pickFont, chooseHomeScreens,
            arrangeLibraryChips, arrangePlaylistTags, customFontPickerSupported, nativeIosInterface, desktop)
    }
}
