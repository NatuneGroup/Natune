package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.intPreferencesKey

// A global lyrics sync nudge applied on top of every song's persisted correction; the per-song
// row always stores the effective shift minus this share.
val GlobalLyricsOffsetMsKey = intPreferencesKey("globalLyricsOffsetMs")
