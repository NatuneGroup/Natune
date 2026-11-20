package app.natune.shared.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.natune.shared.domain.settings.*
import app.natune.shared.ui.resources.*
import app.natune.shared.ui.settings.SettingsController
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.*

/** Writes the same independent seed preference the shared theme consumes, without applying an interface preset. */
@Composable
internal fun IosPaletteEditor(controller: SettingsController, onFailure: () -> Unit) {
    app.natune.shared.ui.settings.ColorPaletteSettings(
        controller, androidx.compose.foundation.isSystemInDarkTheme().let { system ->
            val prefs by controller.store.snapshots.collectAsState()
            app.natune.shared.ui.darkThemeFor(prefs?.get(DarkModeKey), system)
        }, onFailure)
}

@Composable
internal fun IosReleasePage(graph: IosSessionGraph, navigate: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.ios_release_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.ios_release_handoff))
        Button(onClick = { openIosUri("https://github.com/NatuneGroup/Natune/releases", graph::report) }) {
            Text(stringResource(Res.string.ios_release_open))
        }
        TextButton(onClick = { navigate("settings/changelog") }) { Text(stringResource(Res.string.ios_release_changelog)) }
    }
}

@Composable
internal fun IosIconEditor(onFailure: () -> Unit) {
    val uiApp = platform.UIKit.UIApplication.sharedApplication
    var selected by remember { mutableStateOf(uiApp.alternateIconName?.removePrefix("NatuneIcon_") ?: "default") }
    var applying by remember { mutableStateOf<String?>(null) }
    app.natune.shared.ui.settings.LauncherIconSettings(selected, applying,
        supported = { id -> id == "default" || uiApp.supportsAlternateIcons },
        onSelect = { id ->
            if (id != selected && applying == null) {
                applying = id
                uiApp.setAlternateIconName(id.takeUnless { it == "default" }?.let { "NatuneIcon_$it" }, completionHandler = { error ->
                    applying = null
                    if (error == null) selected = id else onFailure()
                })
            }
        },
        onOpenAuthor = { id -> app.natune.shared.ui.settings.SharedLauncherIconCatalog.firstOrNull { it.id == id }
            ?.githubAuthorUrl?.let { openIosUri(it) { onFailure() } } },
    )
}
