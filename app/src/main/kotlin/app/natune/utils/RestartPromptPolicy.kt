/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import app.natune.constants.*
import app.natune.kmp.settings.sharedSnapshot

typealias RestartReason = app.natune.shared.domain.settings.RestartReason
typealias RestartPromptPolicy = app.natune.shared.domain.settings.RestartPromptPolicy

internal fun MutablePreferences.setCustomFont(uri: String, name: String) {
    this[CustomFontUriKey] = uri
    this[CustomFontNameKey] = name
    setFontPreference(AppFontPreference.CUSTOM)
}

/** The shared restart values, read from an Android DataStore snapshot. */
fun restartSettingValues(
    preferences: Preferences,
    bitPerfectSupported: Boolean,
    directUsbDriverAvailable: Boolean = false,
): Map<RestartReason, String> =
    app.natune.shared.domain.settings.restartSettingValues(preferences.sharedSnapshot(), bitPerfectSupported, directUsbDriverAvailable)
