package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Font picker returns a validated, persistently accessible URI + display name, or null on cancel.
 * Hosts retain the running font until restart and release replaced pending grants only after commit.
 */
@Composable
fun AppearanceThemeSettings(
    controller: SettingsController,
    systemDark: Boolean,
    lowRam: Boolean,
    nativeDynamicColors: Boolean,
    wallpaperPermissionNeeded: Boolean,
    navigate: (String) -> Unit,
    openWallpaperSettings: () -> Unit,
    pickCustomFont: suspend () -> Pair<String, String>?,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    var choosingInterface by remember { mutableStateOf(false) }
    var choosingFont by remember { mutableStateOf(false) }
    fun attempt(block: suspend () -> Unit) { scope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onFailure() }
    } }
    suspend fun selectCustomFont() {
        val selected = pickCustomFont() ?: return
        require(selected.first.isNotBlank())
        controller.edit { setCustomFont(selected.first, selected.second) }
    }
    SettingsPage(
        appearanceThemeSettingsRows(preferences, systemDark, lowRam, nativeDynamicColors, wallpaperPermissionNeeded,
            navigate, openWallpaperSettings, { choosingInterface = true }, { choosingFont = true },
            { attempt { selectCustomFont() } }),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier,
    )
    if (choosingInterface) AlertDialog(
        onDismissRequest = { choosingInterface = false },
        title = { Text(stringResource(Res.string.m6_interface_style)) },
        text = { Column {
            InterfaceStyle.entries.forEach { style ->
                TextButton(onClick = { attempt { controller.interfaceStyle(style); choosingInterface = false } }) {
                    Text(stringResource(if (style == InterfaceStyle.APPLE_MUSIC) Res.string.m6_apple_music else Res.string.m6_material_expressive))
                }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingInterface = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
    if (choosingFont) AlertDialog(
        onDismissRequest = { choosingFont = false },
        title = { Text(stringResource(Res.string.m6_font)) },
        text = { Column {
            AppFontPreference.entries.forEach { font ->
                TextButton(onClick = { attempt {
                    if (font == AppFontPreference.CUSTOM && preferences[CustomFontUriKey].isNullOrBlank()) selectCustomFont()
                    else controller.edit { this[FontPreferenceKey] = font.name; this[UseSystemFontKey] = null }
                    choosingFont = false
                } }) {
                    Text(stringResource(when (font) {
                        AppFontPreference.DEFAULT -> Res.string.m6_default
                        AppFontPreference.SYSTEM -> Res.string.m6_system_font
                        AppFontPreference.CUSTOM -> Res.string.m6_custom_font
                    }))
                }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingFont = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
