package moe.rukamori.archivetune.spotify

import android.content.Context
import moe.rukamori.archivetune.utils.PreferenceSnapshot

// Source-compatible names for callers outside this change; the policy belongs to Spotify.
fun PreferenceSnapshot.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()

fun Context.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()
