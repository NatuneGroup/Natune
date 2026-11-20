/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.utils

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import app.natune.constants.AppFontPreference
import app.natune.kmp.settings.sharedSnapshot

typealias RestartScopedSettings = app.natune.shared.domain.settings.RestartScopedSettings

/** Reads the restart-scoped values from an Android DataStore snapshot; a missing one reads as the defaults. */
fun app.natune.shared.domain.settings.RestartScopedSettings.Companion.from(preferences: Preferences?): RestartScopedSettings =
    from(preferences?.sharedSnapshot())

typealias PendingRestart = app.natune.shared.domain.settings.PendingRestart

/** The values this process started with. Read once, on first access, and never again. */
object StartupSettings {
    val values: RestartScopedSettings by lazy {
        // Bounded: a slow first load starts the app on defaults rather than hanging it.
        PreferenceStore.blockUntilLoaded()
        RestartScopedSettings.from(PreferenceStore.snapshot())
    }

    val uiScale: Float get() = values.uiScale
    val fontPreference: AppFontPreference get() = values.fontPreference
    val customFontUri: String get() = values.customFontUri
    val tabletModeEnabled: Boolean get() = values.tabletModeEnabled
    val liquidGlassEnabled: Boolean get() = values.liquidGlassEnabled
    val liquidGlassNavBarEnabled: Boolean get() = values.liquidGlassNavBarEnabled
    val navigationBarFrostedBlur: Boolean get() = values.navigationBarFrostedBlur
    val navigationBarTintFrostedBlur: Boolean get() = values.navigationBarTintFrostedBlur

    fun pending(stored: Preferences?): PendingRestart = PendingRestart.between(values, RestartScopedSettings.from(stored))
}

fun Context.relaunchApp() {
    packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
    }
    kotlin.system.exitProcess(0)
}
