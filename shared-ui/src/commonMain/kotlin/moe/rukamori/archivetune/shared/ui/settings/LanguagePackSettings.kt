package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The Japanese romanization dictionary as the host's download manager reports it. Negative progress is preparing. */
sealed interface JapaneseLanguagePackUiState {
    data object Checking : JapaneseLanguagePackUiState
    data object NotInstalled : JapaneseLanguagePackUiState
    data class Downloading(val progressPercent: Int) : JapaneseLanguagePackUiState
    data object Removing : JapaneseLanguagePackUiState
    data class Installed(val sizeBytes: Long) : JapaneseLanguagePackUiState

    /** A failed removal offers Remove again; any other failure offers a retry of the download. */
    data class Failed(val message: String, val removalFailed: Boolean) : JapaneseLanguagePackUiState
}

/** An optional add-on download, the SpatialFlow typeface, as the host's add-on manager reports it. */
sealed interface AddonDownloadUiState {
    data object Unavailable : AddonDownloadUiState
    data object NotInstalled : AddonDownloadUiState
    data class Downloading(val progressPercent: Int) : AddonDownloadUiState
    data class Installed(val sizeBytes: Long) : AddonDownloadUiState
    data class Failed(val message: String) : AddonDownloadUiState
}

/**
 * Additional downloads: the base language, the optional Japanese romanization dictionary and the SpatialFlow typeface.
 * Downloads, cancels and removals are host-owned. The page shows the reported state and offers only the action that
 * state allows; during a check or removal no action is offered. [formatSize] is the host's locale-aware byte formatter.
 * [spatialFlowFontInstallLabel] lets a host that installs a local file name that action instead of a download.
 */
@Composable
fun LanguagePackSettings(
    controller: SettingsController,
    japanese: JapaneseLanguagePackUiState,
    spatialFlowFont: AddonDownloadUiState,
    formatSize: (Long) -> String,
    onInstallJapanese: () -> Unit,
    onCancelJapanese: () -> Unit,
    onRemoveJapanese: () -> Unit,
    onInstallSpatialFlowFont: suspend () -> Unit,
    onRemoveSpatialFlowFont: suspend () -> Unit,
    onFailure: () -> Unit,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    spatialFlowFontInstallLabel: StringResource = Res.string.m6_language_pack_download,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    fun hostAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    val japaneseValue = when (val state = japanese) {
        JapaneseLanguagePackUiState.Checking -> stringResource(Res.string.m6_language_pack_japanese_checking)
        JapaneseLanguagePackUiState.Removing -> stringResource(Res.string.m6_language_pack_japanese_removing)
        JapaneseLanguagePackUiState.NotInstalled -> stringResource(Res.string.m6_language_pack_japanese_description)
        is JapaneseLanguagePackUiState.Downloading ->
            if (state.progressPercent < 0) stringResource(Res.string.m6_language_pack_japanese_preparing)
            else stringResource(Res.string.m6_language_pack_downloading, state.progressPercent)
        is JapaneseLanguagePackUiState.Installed ->
            stringResource(Res.string.m6_language_pack_installed_size, formatSize(state.sizeBytes))
        is JapaneseLanguagePackUiState.Failed -> state.message
    }
    val japaneseAction: SettingsRow? = when (val state = japanese) {
        JapaneseLanguagePackUiState.Checking, JapaneseLanguagePackUiState.Removing -> null
        JapaneseLanguagePackUiState.NotInstalled -> SettingsRow.Action(
            "language_pack_japanese_action", Res.string.m6_language_pack_download, run = onInstallJapanese,
        )
        is JapaneseLanguagePackUiState.Downloading -> SettingsRow.Action(
            "language_pack_japanese_action", Res.string.m6_cancel, run = onCancelJapanese,
        )
        is JapaneseLanguagePackUiState.Installed -> SettingsRow.Action(
            "language_pack_japanese_action", Res.string.m6_language_pack_remove, run = onRemoveJapanese,
        )
        is JapaneseLanguagePackUiState.Failed ->
            if (state.removalFailed) {
                SettingsRow.Action("language_pack_japanese_action", Res.string.m6_language_pack_remove, run = onRemoveJapanese)
            } else {
                SettingsRow.Action("language_pack_japanese_action", Res.string.m6_retry, run = onInstallJapanese)
            }
    }
    val fontValue = when (val state = spatialFlowFont) {
        AddonDownloadUiState.Unavailable -> stringResource(Res.string.m6_additional_downloads_unavailable)
        AddonDownloadUiState.NotInstalled -> stringResource(Res.string.m6_additional_downloads_spatialflow_font_description)
        is AddonDownloadUiState.Downloading -> stringResource(Res.string.m6_language_pack_downloading, state.progressPercent)
        is AddonDownloadUiState.Installed -> stringResource(Res.string.m6_language_pack_installed_size, formatSize(state.sizeBytes))
        is AddonDownloadUiState.Failed -> state.message
    }
    val fontAction: SettingsRow? = when (spatialFlowFont) {
        AddonDownloadUiState.Unavailable, is AddonDownloadUiState.Downloading -> null
        is AddonDownloadUiState.Installed -> SettingsRow.Action(
            "additional_downloads_spatialflow_action", Res.string.m6_language_pack_remove,
            run = { hostAction(onRemoveSpatialFlowFont) },
        )
        AddonDownloadUiState.NotInstalled, is AddonDownloadUiState.Failed -> SettingsRow.Action(
            "additional_downloads_spatialflow_action", spatialFlowFontInstallLabel,
            run = { hostAction(onInstallSpatialFlowFont) },
        )
    }
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("language_pack_base", Res.string.m6_language_pack_base_group))
        add(SettingsRow.Status(
            "language_pack_english", Res.string.m6_language_english,
            stringResource(Res.string.m6_language_pack_built_in),
        ))
        add(SettingsRow.Header("language_pack_optional", Res.string.m6_language_pack_optional_group))
        add(SettingsRow.Status(
            "language_pack_japanese", Res.string.m6_language_pack_japanese, japaneseValue, fullText = true,
        ))
        japaneseAction?.let { add(it) }
        add(SettingsRow.Status(
            "language_pack_other", Res.string.m6_language_pack_other_romanization,
            stringResource(Res.string.m6_language_pack_other_romanization_description),
        ))
        add(SettingsRow.Header("additional_downloads_fonts", Res.string.m6_additional_downloads_player_fonts))
        add(SettingsRow.Status(
            "additional_downloads_spatialflow_font", Res.string.m6_additional_downloads_spatialflow_font,
            fontValue, fullText = true,
        ))
        fontAction?.let { add(it) }
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )
}
