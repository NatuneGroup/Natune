package moe.rukamori.archivetune.ui.utils

import moe.rukamori.archivetune.constants.AudioSourceType

internal fun providerDisplayName(source: AudioSourceType): String =
    when (source) {
        AudioSourceType.TIDAL -> "Tidal (HiFi FLAC)"
        AudioSourceType.QOBUZ -> "Qobuz (Studio FLAC)"
        AudioSourceType.QOBUZ_BACKUP -> "Qobuz Backup (Mirror)"
        AudioSourceType.DEEZER -> "Deezer (Lossless FLAC)"
        AudioSourceType.APPLE -> "Apple Music (Catalogue)"
        AudioSourceType.AMAZON -> "Amazon Music (Streaming)"
        AudioSourceType.MODULES -> "Modules"
        AudioSourceType.QQ -> "QQ Music (Streaming)"
        AudioSourceType.SPOTIFY -> "Spotify (Streaming account)"
        AudioSourceType.JIOSAAVN -> "JioSaavn (AAC)"
        AudioSourceType.YOUTUBE -> "YouTube Music (Standard)"
    }

internal fun providerNote(source: AudioSourceType): String =
    when (source) {
        AudioSourceType.TIDAL -> "Direct API • SourcePool"
        AudioSourceType.QOBUZ -> "SourcePool • Akamai CDN"
        AudioSourceType.QOBUZ_BACKUP -> "Secondary mirror • endpoint chain"
        AudioSourceType.DEEZER -> "Lossless resolver"
        AudioSourceType.APPLE -> "Metadata & lyrics tier"
        AudioSourceType.AMAZON -> "Amazon Music Stream API"
        AudioSourceType.MODULES -> "Installed source modules"
        AudioSourceType.QQ -> "QQ Music resolver"
        AudioSourceType.SPOTIFY -> "Experimental • separate streaming account"
        AudioSourceType.JIOSAAVN -> "AAC resolver"
        AudioSourceType.YOUTUBE -> "Ultimate zero-skip fallback"
    }

