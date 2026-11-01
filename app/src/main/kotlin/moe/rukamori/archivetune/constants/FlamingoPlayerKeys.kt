package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Flamingo player settings. Upstream reads both with the same defaults written here — the volume
 * bar on, the backdrop effect off — so a fresh install looks the way the style's reference does.
 */
val FlamingoShowVolumeBarKey = booleanPreferencesKey("flamingoNowPlayingShowVolumeBar")
val FlamingoBackgroundEffectKey = booleanPreferencesKey("flamingoNowPlayingBackgroundEffect")
