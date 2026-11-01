package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Audio playback speed/pitch applied by the tempo-and-pitch dialog, stored only while the dialog's
 * opt-in "remember speed and pitch" toggle holds; MusicService restores them on the next launch.
 * Persisted names match the shared-domain keys so every host reads the same values.
 */
val PlaybackSpeedKey = floatPreferencesKey("playbackSpeed")
val PlaybackPitchKey = floatPreferencesKey("playbackPitch")
val TempoPitchRememberedKey = booleanPreferencesKey("tempoPitchRemembered")

/** Named tempo/pitch pairs saved from the dialog, as a JSON array of the shared preset model. */
val TempoPitchPresetsJsonKey = stringPreferencesKey("tempoPitchPresetsJson")
