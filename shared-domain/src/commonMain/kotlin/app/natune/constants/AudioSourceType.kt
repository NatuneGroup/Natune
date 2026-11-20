package app.natune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey

/** The Android code's name for the shared source enum, so both sides pass the same type. */
typealias AudioSourceType = app.natune.shared.domain.source.AudioSourceType

val ModulesEnabledKey = booleanPreferencesKey("modulesEnabled")
