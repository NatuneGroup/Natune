package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.*

/** Writes the same independent seed preference the shared theme consumes, without applying an interface preset. */
@Composable
internal fun IosPaletteEditor(controller: SettingsController, onFailure: () -> Unit) {
    moe.rukamori.archivetune.shared.ui.settings.ColorPaletteSettings(
        controller, androidx.compose.foundation.isSystemInDarkTheme().let { system ->
            val prefs by controller.store.snapshots.collectAsState()
            moe.rukamori.archivetune.shared.ui.darkThemeFor(prefs?.get(DarkModeKey), system)
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
    val app = platform.UIKit.UIApplication.sharedApplication
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(Res.string.ios_icon_title), style = MaterialTheme.typography.headlineSmall)
        TextButton(enabled = app.supportsAlternateIcons, onClick = {
            app.setAlternateIconName(null, completionHandler = { error -> if (error != null) onFailure() })
        }) { Text(stringResource(Res.string.ios_icon_default)) }
        TextButton(enabled = app.supportsAlternateIcons, onClick = {
            app.setAlternateIconName("ArchiveTuneAndroid", completionHandler = { error -> if (error != null) onFailure() })
        }) { Text(stringResource(Res.string.ios_icon_android)) }
    }
}
