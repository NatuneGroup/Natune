/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

import moe.rukamori.archivetune.shared.domain.source.AudioSourceConfig
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

fun AudioSourceType.enabledPreference(): BooleanPreferenceKey = when (this) {
    AudioSourceType.TIDAL -> TidalEnabledKey
    AudioSourceType.QOBUZ -> QobuzEnabledKey
    AudioSourceType.QOBUZ_BACKUP -> QobuzBackupEnabledKey
    AudioSourceType.DEEZER -> DeezerEnabledKey
    AudioSourceType.APPLE -> AppleMusicSourceEnabledKey
    AudioSourceType.JIOSAAVN -> JioSaavnEnabledKey
    AudioSourceType.AMAZON -> AmazonEnabledKey
    AudioSourceType.QQ -> QqMusicEnabledKey
    AudioSourceType.SPOTIFY -> SpotifyStreamEnabledKey
    AudioSourceType.YOUTUBE -> YouTubeSourceEnabledKey
}

fun PreferenceSnapshot.audioSourceEnabled(source: AudioSourceType, spotifyStreamingReady: Boolean): Boolean =
    this[source.enabledPreference(), AudioSourceConfig.DEFAULT_ENABLED.getValue(source)] &&
        (source != AudioSourceType.SPOTIFY || spotifyStreamingReady)

/** Reordering explicitly enables the first source, except the separately authenticated Spotify source. */
fun PreferenceEditor.setAudioSourcePriority(order: List<AudioSourceType>) {
    require(order.size == AudioSourceType.entries.size && order.toSet() == AudioSourceType.entries.toSet())
    this[AudioSourceOrderKey] = order.joinToString(",") { it.name }
    order.first().takeUnless { it == AudioSourceType.SPOTIFY }?.let { this[it.enabledPreference()] = true }
}
