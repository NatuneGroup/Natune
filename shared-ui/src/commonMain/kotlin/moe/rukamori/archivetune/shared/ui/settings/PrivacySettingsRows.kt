package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.math.roundToInt

/**
 * The desktop Privacy page. Pausing listening history stops the listen driver from recording; pausing search history
 * stops the search owner from keeping queries. History duration moved here from the playback page, where it sat beside
 * the crossfade controls it has nothing to do with.
 *
 * Clearing stored history is a host action and stays outside this list. A phone-only row (low data mode for a metered
 * connection) is not offered: this host has no metered network switch.
 */
fun desktopPrivacySettingsRows(): List<SettingsRow> = listOf(
    SettingsRow.Toggle("pause_listen_history", Res.string.m6_settings_desktop_pause_listen, PauseListenHistoryKey, false),
    SettingsRow.Toggle("pause_search_history", Res.string.m6_settings_desktop_pause_search, PauseSearchHistoryKey, false),
    SettingsRow.Number("history_duration", Res.string.m6_history_duration, HistoryDuration, 30, 1..60,
        read = {
            (it[HistoryDuration] ?: it[HISTORY_DURATION_LEGACY_FLOAT_KEY]?.takeIf(Float::isFinite)?.roundToInt() ?: 30)
                .coerceIn(1, 60)
        },
        write = { this[HistoryDuration] = it; this[HISTORY_DURATION_LEGACY_FLOAT_KEY] = null }),
    SettingsRow.Toggle("sync_yt_history", Res.string.m6_sync_history, SyncPlaybackToYouTubeHistoryKey,
        PrivacyPreferenceDefaults.SYNC_YOUTUBE_HISTORY),
)
