package moe.rukamori.archivetune.spotify

import android.content.Context
import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.utils.PreferenceSnapshot

// Source-compatible names for callers outside this change; the policy belongs to Spotify.
@Composable
fun rememberSpotifyMetadataExtensionEnabled(): Boolean = rememberSpotifyCatalogEnabled()

fun PreferenceSnapshot.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()

fun Context.spotifyMetadataExtensionEnabled(): Boolean = spotifyCatalogEnabled()

@Composable
fun rememberSpotifyPlaybackExtensionAvailable(): Boolean = rememberSpotifyPlaybackAvailable()
