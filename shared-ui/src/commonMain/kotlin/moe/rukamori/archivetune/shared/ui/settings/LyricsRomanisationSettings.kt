package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackState
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapanesePackFailure
import moe.rukamori.archivetune.shared.domain.settings.aiLyricsSessionSettings
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The romanisation sub-page: every per-language toggle of the original `LyricsRomanisationSettings`, gated the same
 * way — an active AI romanisation greys the whole set behind the override note, and the Japanese toggle stays locked
 * until the offline dictionary reports Installed (or the user already turned it on). A null pack state flow means the
 * host has no pack manager, in which case Japanese romanization is unavailable and the row says so.
 */
@Composable
fun LyricsRomanisationSettings(
    controller: SettingsController,
    japanesePack: StateFlow<JapaneseLanguagePackState>?,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val pack = japanesePack?.collectAsState()?.value
    SettingsPage(
        rows = romanizationSettingsRows(
            aiHandled = preferences.aiLyricsSessionSettings().romanization.active,
            japanesePackReady = pack is JapaneseLanguagePackState.Installed,
            preferences = preferences,
        ),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
    )
}

/** The domain pack state as the settings page's UI state; `reason` picks one of the original failure strings. */
@Composable
fun japanesePackUiState(state: JapaneseLanguagePackState): JapaneseLanguagePackUiState = when (state) {
    JapaneseLanguagePackState.Checking -> JapaneseLanguagePackUiState.Checking
    JapaneseLanguagePackState.NotInstalled -> JapaneseLanguagePackUiState.NotInstalled
    is JapaneseLanguagePackState.Downloading -> JapaneseLanguagePackUiState.Downloading(state.progressPercent)
    JapaneseLanguagePackState.Removing -> JapaneseLanguagePackUiState.Removing
    is JapaneseLanguagePackState.Installed -> JapaneseLanguagePackUiState.Installed(state.sizeBytes)
    is JapaneseLanguagePackState.Failed -> JapaneseLanguagePackUiState.Failed(
        message = stringResource(
            when (state.reason) {
                JapanesePackFailure.INVALID -> Res.string.m6_japanese_pack_invalid
                JapanesePackFailure.INSTALL -> Res.string.m6_japanese_pack_failed
                JapanesePackFailure.REMOVE -> Res.string.m6_japanese_pack_remove_failed
            },
        ),
        removalFailed = state.removalFailed,
    )
}
