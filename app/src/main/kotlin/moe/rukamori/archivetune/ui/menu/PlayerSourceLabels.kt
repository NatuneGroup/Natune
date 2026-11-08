package moe.rukamori.archivetune.ui.menu

import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioSourceType

internal fun AudioSourceType.sourceLabelRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.string.source_tidal
        AudioSourceType.QOBUZ -> R.string.source_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.string.source_qobuz_backup
        AudioSourceType.DEEZER -> R.string.source_deezer
        AudioSourceType.APPLE -> R.string.source_apple_music
        AudioSourceType.AMAZON -> R.string.source_amazon
        AudioSourceType.MODULES -> R.string.modules_title
        AudioSourceType.QQ -> R.string.source_qq_music
        AudioSourceType.SPOTIFY -> R.string.source_spotify_stream
        AudioSourceType.JIOSAAVN -> R.string.source_jiosaavn
        AudioSourceType.YOUTUBE -> R.string.source_youtube
    }

internal fun AudioSourceType.sourceIconRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.drawable.provider_tidal
        AudioSourceType.QOBUZ -> R.drawable.provider_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.drawable.provider_qobuz
        AudioSourceType.DEEZER -> R.drawable.provider_deezer
        AudioSourceType.APPLE -> R.drawable.ic_music
        // No dedicated Amazon Music mark ships in drawable/ yet; ic_music is the same
        // stand-in APPLE uses above for the same reason.
        AudioSourceType.AMAZON -> R.drawable.ic_music
        AudioSourceType.MODULES -> R.drawable.extension
        AudioSourceType.QQ -> R.drawable.ic_music
        AudioSourceType.SPOTIFY -> R.drawable.spotify_icon
        AudioSourceType.JIOSAAVN -> R.drawable.provider_jiosaavn
        AudioSourceType.YOUTUBE -> R.drawable.play
    }

