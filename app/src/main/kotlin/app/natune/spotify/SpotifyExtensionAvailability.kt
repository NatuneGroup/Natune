package app.natune.spotify

import android.content.Context
import app.natune.utils.PreferenceSnapshot

// Source-compatible names for callers outside this change; the policy belongs to Spotify.
fun PreferenceSnapshot.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()

fun Context.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()
