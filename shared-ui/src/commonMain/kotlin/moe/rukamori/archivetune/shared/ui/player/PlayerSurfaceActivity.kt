/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

data class PlayerSurfaceActivity(
    val artwork: Boolean,
    val background: Boolean,
    val fullLyrics: Boolean,
    val compactLyrics: Boolean,
)

/** The shell, not an individual look or native decoder, knows which mounted surfaces are occluded. */
fun playerSurfaceActivity(
    style: PlayerDesignStyle,
    panel: PlayerPanel,
    fullscreenLyrics: Boolean,
    landscape: Boolean,
    active: Boolean,
): PlayerSurfaceActivity {
    val mainVisible = active && !fullscreenLyrics && (landscape || panel == PlayerPanel.ARTWORK)
    val flamingo = style == PlayerDesignStyle.FLAMINGO
    val inlineLyrics = style in setOf(
        PlayerDesignStyle.APPLE_MUSIC, PlayerDesignStyle.BITCHORD, PlayerDesignStyle.TIKTOK,
        PlayerDesignStyle.SIMPMUSIC, PlayerDesignStyle.SPATIALFLOW, PlayerDesignStyle.LOOPER,
        PlayerDesignStyle.V5, PlayerDesignStyle.V8,
    )
    return PlayerSurfaceActivity(
        artwork = mainVisible && (!flamingo || panel == PlayerPanel.ARTWORK),
        background = mainVisible || (active && !fullscreenLyrics && flamingo),
        fullLyrics = active && (fullscreenLyrics || panel == PlayerPanel.LYRICS ||
            (landscape && style == PlayerDesignStyle.METROLIST_KMP && panel != PlayerPanel.QUEUE)),
        compactLyrics = mainVisible && inlineLyrics,
    )
}

/** Called inside the shell so M3 receives visibility for actual mounted pages, not host guesses. */
typealias PlayerSlotFactory = @Composable (PreferenceSnapshot, PlayerSurfaceActivity) -> PlayerSlots
