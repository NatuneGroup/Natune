package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import moe.rukamori.archivetune.shared.ui.settings.PhonePlaybackSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/** The host observes output and opens the native equalizer; commonMain owns all rows. */
@Composable
internal fun IosPlaybackSettingsPage(controller: SettingsController, graph: IosSessionGraph,
    navigate: (String) -> Unit, arrangeArtwork: () -> Unit, onFailure: () -> Unit, scrollTo: String?) {
    val format by graph.playbackPort.outputFormat.collectAsState()
    PhonePlaybackSettings(controller, format.pcmRate, format.routeRate.toInt(), graph.equalizer::openSheet,
        navigate, arrangeArtwork, onFailure, scrollTo)
}
