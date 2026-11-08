package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.playbackSettingsRows
import org.jetbrains.compose.resources.stringResource

/** Every visible switch must have an iOS reader; preserve its saved value for portable backups. */
@Composable
internal fun IosPlaybackSettingsPage(
    controller: SettingsController,
    graph: IosSessionGraph,
    navigate: (String) -> Unit,
    arrangeArtwork: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    val unsupported = setOf("skip_silence", "audio_normalization", "audio_offload", "enable_video_playback",
        "enable_pip_mode", "bluetooth_auto_start", "stop_task_clear", "wakelock", "reject_suspected_upscaled_audio")
    val rows = playbackSettingsRows(preferences, false, false, false, navigate, arrangeArtwork,
        { navigate("library/tags") }, onOpenEqualizer = { graph.equalizer.openSheet() }).map { row ->
        if (row is SettingsRow.Toggle && row.anchor in unsupported) SettingsRow.Toggle(
            row.anchor, row.title, row.key, row.default, enabled = false, read = { false }, write = row.write,
            description = if (row.anchor == "audio_normalization") Res.string.ios_normalization_unavailable else Res.string.ios_playback_os_unavailable,
        ) else row
    } + SettingsRow.Status("ios_processing_limits", Res.string.ios_dsp_supported,
        stringResource(Res.string.ios_dsp_limits), fullText = true)
    SettingsPage(rows, preferences, controller, scrollTo, onFailure = onFailure)
}
