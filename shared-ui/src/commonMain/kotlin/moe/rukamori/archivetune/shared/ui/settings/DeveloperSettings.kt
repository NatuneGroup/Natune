/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Developer switches keep their persisted identities and render their diagnostics only while enabled.
 * The acoustic audit, Discord debug lines and nerd stats need the live playback target and runtime, so
 * the host supplies them as slots. An unsupplied slot is a compile error, never a silent empty panel.
 */
@Composable
fun DeveloperSettings(
    controller: SettingsController,
    navigate: (String) -> Unit,
    playerAvailable: Boolean,
    acousticQualitySection: @Composable (active: Boolean) -> Unit,
    discordDebugSection: @Composable () -> Unit,
    nerdStatsSection: @Composable () -> Unit,
    resumed: Boolean,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    // Collapsing removes the audit from composition, which is what stops its work and frame clocks.
    var acousticExpanded by remember { mutableStateOf(false) }
    SettingsPage(
        rows = developerSettingsRows(onToggleAcoustic = { acousticExpanded = !acousticExpanded }, navigate = navigate),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        onFailure = onFailure,
        modifier = modifier,
        extraItems = {
            if (acousticExpanded) {
                item(key = "acoustic_quality_section") { acousticQualitySection(resumed) }
            }
            if (preferences[InlineDevShowDiscordDebugKey, false]) {
                item(key = "discord_debug_section") { discordDebugSection() }
            }
            if (playerAvailable && preferences[InlineDevShowNerdStatsKey, false]) {
                item(key = "nerd_stats_section") { nerdStatsSection() }
            }
        },
    )
}

private fun developerSettingsRows(
    onToggleAcoustic: () -> Unit,
    navigate: (String) -> Unit,
): List<SettingsRow> = listOf(
    SettingsRow.Action(
        "acoustic_quality", Res.string.m6_dev_acoustic_quality, run = onToggleAcoustic,
        description = Res.string.m6_dev_acoustic_quality_desc,
    ),
    SettingsRow.Toggle(
        "dev_show_discord_debug", Res.string.m6_dev_show_discord_debug, InlineDevShowDiscordDebugKey, false,
        description = Res.string.m6_dev_show_discord_debug_desc,
    ),
    SettingsRow.Toggle(
        "dev_show_nerd_stats", Res.string.m6_dev_show_nerd_stats, InlineDevShowNerdStatsKey, false,
        description = Res.string.m6_dev_show_nerd_stats_desc,
    ),
    SettingsRow.Toggle(
        "show_codec_on_player", Res.string.m6_dev_show_codec, ShowCodecOnPlayerKey, true,
        description = Res.string.m6_dev_show_codec_desc,
    ),
    SettingsRow.Action(
        "debug_logs", Res.string.m6_dev_debug_logs, run = { navigate("settings/logcat") },
        description = Res.string.m6_dev_debug_logs_desc,
    ),
)
