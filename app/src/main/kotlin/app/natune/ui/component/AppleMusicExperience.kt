/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package app.natune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.MutablePreferences
import app.natune.constants.InterfaceStyle
import app.natune.constants.LibraryStyle
import app.natune.constants.LibraryStyleKey
import app.natune.kmp.settings.sharedSnapshot
import app.natune.shared.domain.settings.InterfaceStyle as SharedInterfaceStyle
import app.natune.shared.domain.settings.applyInterfaceStyle as applySharedInterfaceStyle
import app.natune.shared.domain.settings.appleMusicMenus
import app.natune.shared.domain.settings.appleMusicPalette
import app.natune.shared.domain.settings.appleMusicSearchBar
import app.natune.shared.domain.settings.interfaceStyle
import app.natune.shared.domain.settings.libraryStyle as sharedLibraryStyle
import app.natune.shared.domain.settings.PlayerMenuStyle
import app.natune.shared.domain.settings.playerMenuStyle as sharedPlayerMenuStyle
import app.natune.shared.domain.settings.setPlayerMenuStyle
import app.natune.utils.PreferenceSnapshot
import app.natune.utils.PreferenceStore
import app.natune.utils.applySharedPreferenceEdit
import app.natune.utils.dataStore
import app.natune.utils.rememberPreferenceSnapshot

/**
 * The Library tab's layout style and its setter.
 *
 * A read helper rather than the raw preference so the call sites cannot disagree about the key, the
 * default or the seeding rule.
 */
@Composable
fun rememberLibraryStyle(): Pair<LibraryStyle, (LibraryStyle) -> Unit> {
    return rememberPreferenceSnapshot().libraryStyle()
}

/** [rememberLibraryStyle] read from a page's [PreferenceSnapshot] instead of its own collectors. */
fun PreferenceSnapshot.libraryStyle(): Pair<LibraryStyle, (LibraryStyle) -> Unit> {
    val (_, setStyle) = enumState(LibraryStyleKey, defaultValue = LibraryStyle.DEFAULT)
    return LibraryStyle.valueOf(current.sharedSnapshot().sharedLibraryStyle().name) to setStyle
}

/**
 * Preset status only. Renderers must read the preference for their own surface, not this indicator.
 */
@Composable
fun rememberAppleMusicExperience(): Boolean {
    return rememberPreferenceSnapshot().current.sharedSnapshot().interfaceStyle() == SharedInterfaceStyle.APPLE_MUSIC
}

@Composable
fun rememberAppleMusicMenus(): Boolean = rememberPreferenceSnapshot().current.sharedSnapshot().appleMusicMenus()

@Composable
fun rememberAppleMusicSearchBar(): Boolean = rememberPreferenceSnapshot().current.sharedSnapshot().appleMusicSearchBar()

@Composable
fun rememberAppleMusicPalette(): Boolean = rememberPreferenceSnapshot().current.sharedSnapshot().appleMusicPalette()

/** The three-dot and lyrics menu look, read from a page's [PreferenceSnapshot]. */
fun PreferenceSnapshot.playerMenuStyle(): PlayerMenuStyle = current.sharedSnapshot().sharedPlayerMenuStyle()

@Composable
fun rememberPlayerMenuStyle(): PlayerMenuStyle = rememberPreferenceSnapshot().playerMenuStyle()

/** Writes the menu look through the shared rule, so the legacy Apple menus toggle moves with the choice. */
@Composable
fun rememberPlayerMenuStyleSetter(): (PlayerMenuStyle) -> Unit {
    val dataStore = LocalContext.current.dataStore
    return { style -> PreferenceStore.launchEdit(dataStore) { applySharedPreferenceEdit { setPlayerMenuStyle(style) } } }
}

internal fun MutablePreferences.applyInterfaceStyle(style: InterfaceStyle) {
    applySharedPreferenceEdit { applySharedInterfaceStyle(SharedInterfaceStyle.valueOf(style.name)) }
}

@Composable
fun rememberInterfaceStyle(): Pair<InterfaceStyle, (InterfaceStyle) -> Unit> {
    return rememberInterfaceStyle(rememberPreferenceSnapshot())
}

/**
 * [rememberInterfaceStyle] read from a page's [PreferenceSnapshot]; the write still goes through the
 * coordinated experience transaction.
 */
@Composable
fun rememberInterfaceStyle(prefs: PreferenceSnapshot): Pair<InterfaceStyle, (InterfaceStyle) -> Unit> {
    val dataStore = LocalContext.current.dataStore
    val style = InterfaceStyle.valueOf(prefs.current.sharedSnapshot().interfaceStyle().name)
    return style to { selected -> PreferenceStore.launchEdit(dataStore) { applyInterfaceStyle(selected) } }
}
