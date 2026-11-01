package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.PlayerStyleSetting
import moe.rukamori.archivetune.constants.honors
import moe.rukamori.archivetune.shared.ui.player.MetrolistPresentation
import moe.rukamori.archivetune.utils.rememberEnumPreference

@Composable
internal fun playerDesignStyleLabel(style: PlayerDesignStyle): String =
    stringResource(
        when (style) {
            PlayerDesignStyle.V1 -> R.string.player_design_v1
            PlayerDesignStyle.V2 -> R.string.player_design_v2
            PlayerDesignStyle.V3 -> R.string.player_design_v3
            PlayerDesignStyle.V4 -> R.string.player_design_v4
            PlayerDesignStyle.V5 -> R.string.player_design_v5
            PlayerDesignStyle.V6 -> R.string.player_design_v6
            PlayerDesignStyle.V7 -> R.string.player_design_v7
            PlayerDesignStyle.V8 -> R.string.player_design_v8
            PlayerDesignStyle.V9 -> R.string.player_design_v9
            PlayerDesignStyle.APPLE_MUSIC -> R.string.player_design_apple_music
            PlayerDesignStyle.V10 -> R.string.player_design_v10
            PlayerDesignStyle.BITCHORD -> R.string.player_design_bitchord
            PlayerDesignStyle.TIKTOK -> R.string.player_design_tiktok
            PlayerDesignStyle.SIMPMUSIC -> R.string.player_design_simpmusic
            PlayerDesignStyle.SPATIALFLOW -> R.string.player_design_spatialflow
            PlayerDesignStyle.LOOPER -> R.string.player_design_looper
            PlayerDesignStyle.FLAMINGO -> R.string.player_design_flamingo
            PlayerDesignStyle.METROLIST_KMP -> R.string.player_design_metrolist_kmp
        },
    )

@Composable
internal fun metrolistPresentationLabel(presentation: MetrolistPresentation): String =
    stringResource(
        when (presentation) {
            MetrolistPresentation.MODERN -> R.string.metrolist_presentation_modern
            MetrolistPresentation.VINTAGE -> R.string.metrolist_presentation_vintage
        },
    )

/** The short "Not used by the … player" line for a row the selected style ignores, or null when it reads the row. */
@Composable
internal fun playerSettingNote(style: PlayerDesignStyle, setting: PlayerStyleSetting): String? =
    if (style.honors(setting)) null else stringResource(R.string.player_setting_not_used, playerDesignStyleLabel(style))

/** For rows on pages that do not otherwise read the selected player style. */
@Composable
internal fun selectedPlayerSettingNote(setting: PlayerStyleSetting): String? {
    val style by rememberEnumPreference(PlayerDesignStyleKey, PlayerDesignStyle.Default)
    return playerSettingNote(style, setting)
}
