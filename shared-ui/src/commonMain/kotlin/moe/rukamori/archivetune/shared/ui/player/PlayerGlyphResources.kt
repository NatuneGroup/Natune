package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource

internal val LocalPlayerGlyphStyle = staticCompositionLocalOf { PlayerDesignStyle.Default }

internal fun transportDrawable(symbol: PlayerSymbol, style: PlayerDesignStyle): DrawableResource? {
    if (symbol == PlayerSymbol.REPLAY) return Res.drawable.reference_player_replay
    val set = when (style) {
        PlayerDesignStyle.APPLE_MUSIC -> listOf(Res.drawable.reference_player_play, Res.drawable.reference_player_pause,
            Res.drawable.reference_apple_skip_previous, Res.drawable.reference_apple_skip_next)
        PlayerDesignStyle.V7 -> listOf(Res.drawable.reference_play, Res.drawable.reference_pause,
            Res.drawable.reference_skip_previous, Res.drawable.reference_skip_next)
        PlayerDesignStyle.SPATIALFLOW -> listOf(Res.drawable.reference_spatialflow_ic_play, Res.drawable.reference_spatialflow_ic_pause,
            Res.drawable.reference_spatialflow_ic_skip_previous, Res.drawable.reference_spatialflow_ic_skip_next)
        PlayerDesignStyle.FLAMINGO -> listOf(Res.drawable.reference_flamingo_np_play, Res.drawable.reference_flamingo_np_pause,
            Res.drawable.reference_flamingo_np_rewind, Res.drawable.reference_flamingo_np_fforward)
        PlayerDesignStyle.SIMPMUSIC -> listOf(Res.drawable.reference_simpmusic_play_circle, Res.drawable.reference_simpmusic_pause_circle,
            Res.drawable.reference_simpmusic_skip_previous, Res.drawable.reference_simpmusic_skip_next)
        PlayerDesignStyle.TIKTOK -> listOf(Res.drawable.solar_play_linear, Res.drawable.solar_pause_linear,
            Res.drawable.reference_player_skip_previous, Res.drawable.reference_player_skip_next)
        else -> listOf(Res.drawable.reference_player_play, Res.drawable.reference_player_pause,
            Res.drawable.reference_player_skip_previous, Res.drawable.reference_player_skip_next)
    }
    return when (symbol) {
        PlayerSymbol.PLAY -> set[0]
        PlayerSymbol.PAUSE -> set[1]
        PlayerSymbol.PREVIOUS -> set[2]
        PlayerSymbol.NEXT -> set[3]
        else -> null
    }
}
