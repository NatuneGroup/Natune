/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.natune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.natune.constants.LyricsMode
import app.natune.constants.LyricsModeKey
import app.natune.ui.component.LyricsAlternate
import app.natune.ui.component.NatuneLyricsEnhanced
import app.natune.ui.component.LyricsV2
import app.natune.ui.player.simpmusic.SimpMusicLyrics
import app.natune.utils.rememberEnumPreference

/**
 * The full-screen lyrics renderer the Lyrics settings select — [LyricsEnhanced] (word sweep,
 * tap-to-seek, auto-scroll) unless the user picked another mode. The shared lyrics page and
 * Bitchord's lyrics window both draw through this, so the mode-to-renderer mapping exists once.
 */
@Composable
fun SelectedLyrics(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    textSizeOverride: Float? = null,
) {
    val lyricsMode by rememberEnumPreference(LyricsModeKey, LyricsMode.ENHANCED)
    when (lyricsMode) {
        LyricsMode.ENHANCED ->
            NatuneLyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
                textSizeOverride = textSizeOverride,
            )

        LyricsMode.SPOTIFY ->
            LyricsAlternate(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
                textSizeOverride = textSizeOverride,
                spotifyStyle = true,
            )

        LyricsMode.V2 ->
            LyricsV2(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
            )

        LyricsMode.SIMPMUSIC ->
            SimpMusicLyrics(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
            )
    }
}
