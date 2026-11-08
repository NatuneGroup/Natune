package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey

enum class AudioSourceType {
    TIDAL,
    QOBUZ,
    QOBUZ_BACKUP,
    DEEZER,
    APPLE,
    AMAZON,
    QQ,
    SPOTIFY,
    JIOSAAVN,
    MODULES,
    YOUTUBE,
}

val ModulesEnabledKey = booleanPreferencesKey("modulesEnabled")
