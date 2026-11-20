/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package app.natune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.natune.lyrics.LyricsEntry
import app.natune.lyrics.LyricsUtils
import app.natune.playback.PlayerConnection

/**
 * The current song's lyrics parsed into timed lines, or an empty list when there are none or they
 * are unsynced — the pane needs timings, so plain-text lyrics do not qualify.
 */
@Composable
fun rememberInlineLyricLines(playerConnection: PlayerConnection): List<LyricsEntry> {
    val entity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val text = entity?.lyrics?.trim()?.takeIf { it.isNotBlank() }
    return remember(text) {
        when {
            text == null -> emptyList()
            LyricsUtils.isTtml(text) ->
                LyricsUtils.parseTtml(text, playerConnection.player.duration.takeIf { it > 0 }?.toInt())
            LyricsUtils.isLineSyncedLrc(text) -> LyricsUtils.parseLyrics(text)
            else -> emptyList()
        }
    }
}
