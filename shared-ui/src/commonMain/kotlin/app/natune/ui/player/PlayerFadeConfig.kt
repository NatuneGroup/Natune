package app.natune.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.natune.constants.PlayerDesignStyle

/** Per-style fade thresholds for title/artist marquee. */
data class FadeThresholds(
    val titleMinChars: Int,
    val artistMinChars: Int,
    val fadeWidth: Dp = 24.dp,
)

object PlayerFadeConfig {
    val forStyle: Map<PlayerDesignStyle, FadeThresholds> = mapOf(
        PlayerDesignStyle.V1 to FadeThresholds(titleMinChars = 22, artistMinChars = 24),
        PlayerDesignStyle.V2 to FadeThresholds(titleMinChars = 24, artistMinChars = 26),
        PlayerDesignStyle.V3 to FadeThresholds(titleMinChars = 22, artistMinChars = 24),
        PlayerDesignStyle.V4 to FadeThresholds(titleMinChars = 24, artistMinChars = 26),
        PlayerDesignStyle.V5 to FadeThresholds(titleMinChars = 20, artistMinChars = 22),
        PlayerDesignStyle.V6 to FadeThresholds(titleMinChars = 26, artistMinChars = 28),
        PlayerDesignStyle.V7 to FadeThresholds(titleMinChars = 28, artistMinChars = 30),
        PlayerDesignStyle.V8 to FadeThresholds(titleMinChars = 28, artistMinChars = 30),
        PlayerDesignStyle.V9 to FadeThresholds(titleMinChars = 30, artistMinChars = 32),
        PlayerDesignStyle.V10 to FadeThresholds(titleMinChars = 32, artistMinChars = 34),
        PlayerDesignStyle.APPLE_MUSIC to FadeThresholds(titleMinChars = 28, artistMinChars = 30),
    )

    val miniPlayer = FadeThresholds(titleMinChars = 22, artistMinChars = 24, fadeWidth = 16.dp)

    fun forStyle(style: PlayerDesignStyle): FadeThresholds =
        forStyle[style] ?: FadeThresholds(titleMinChars = 24, artistMinChars = 26)
}
