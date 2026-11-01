package moe.rukamori.archivetune.shared.ui.host

import moe.rukamori.archivetune.shared.data.provider.AudioAttempt
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.domain.settings.AudioSourceOrderKey
import moe.rukamori.archivetune.shared.domain.settings.AmazonAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicQualityKey
import moe.rukamori.archivetune.shared.domain.settings.DeezerAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.SaavnAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceAmazonPickKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceOverrideKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceQobuzBackupVideoIdKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceQobuzTrackIdKey
import moe.rukamori.archivetune.shared.domain.settings.TidalAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.audioSourceEnabled
import moe.rukamori.archivetune.shared.domain.source.AudioSourceConfig
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.PlaybackSourceSnapshot
import moe.rukamori.archivetune.shared.domain.source.SongSourceAmazonPick
import moe.rukamori.archivetune.shared.domain.source.SongSourceOverride
import moe.rukamori.archivetune.shared.domain.source.SongSourceQobuzBackupVideoId
import moe.rukamori.archivetune.shared.domain.source.SongSourceQobuzTrackId
import moe.rukamori.archivetune.shared.domain.source.TrackMatching

/**
 * The source snapshot one resolution reads: the stored order and the stored toggles. Spotify streaming stays off until
 * its account owner reports ready. Without stored settings the built-in defaults apply, with the same Spotify gate.
 */
fun hostSourceSnapshot(preferences: PreferenceSnapshot?, spotifyStreamingReady: Boolean = false): PlaybackSourceSnapshot {
    val order = AudioSourceConfig.parseOrder(preferences?.get(AudioSourceOrderKey))
    val enabled = AudioSourceType.entries.filterTo(linkedSetOf<AudioSourceType>()) { source ->
        when (preferences) {
            null -> AudioSourceConfig.DEFAULT_ENABLED.getValue(source) && source != AudioSourceType.SPOTIFY
            else -> preferences.audioSourceEnabled(source, spotifyStreamingReady = spotifyStreamingReady)
        }
    }
    return PlaybackSourceSnapshot(order = order, enabled = enabled)
}

/**
 * The stored per-source qualities in the vocabulary each repository accepts. A stored value that a repository does not
 * know is left out, so that repository keeps its own default instead of failing the request.
 */
fun hostSourceQualities(preferences: PreferenceSnapshot?): Map<AudioSourceType, String> = buildMap {
    when (preferences?.get(TidalAudioQualityKey) ?: "FLAC") {
        "AAC_320" -> put(AudioSourceType.TIDAL, "HIGH")
        "FLAC" -> put(AudioSourceType.TIDAL, "LOSSLESS")
        "HI_RES_LOSSLESS" -> put(AudioSourceType.TIDAL, "HI_RES_LOSSLESS")
    }
    preferences?.get(DeezerAudioQualityKey)?.takeIf { it in setOf("FLAC", "MP3_320", "MP3_128") }
        ?.let { put(AudioSourceType.DEEZER, it) }
    when (preferences?.get(SaavnAudioQualityKey) ?: "QUALITY_320") {
        "QUALITY_320" -> put(AudioSourceType.JIOSAAVN, "320")
        "QUALITY_160" -> put(AudioSourceType.JIOSAAVN, "160")
        "QUALITY_96" -> put(AudioSourceType.JIOSAAVN, "96")
    }
    preferences?.get(QqMusicAudioQualityKey)?.takeIf { it in setOf("LOSSLESS", "HIGH", "STANDARD") }
        ?.let { put(AudioSourceType.QQ, it) }
    when (preferences?.get(QobuzAudioQualityKey) ?: "FLAC") {
        "FLAC" -> put(AudioSourceType.QOBUZ, "6")
        "HI_RES" -> put(AudioSourceType.QOBUZ, "7")
        "MAX" -> put(AudioSourceType.QOBUZ, "27")
    }
    preferences?.get(AppleMusicQualityKey)?.takeIf { it in setOf("AAC", "LOSSLESS", "HI_RES_LOSSLESS") }
        ?.let { put(AudioSourceType.APPLE, it) }
    preferences?.get(AmazonAudioQualityKey)?.takeIf { it in setOf("ULTRA_HD", "HD", "STANDARD") }
        ?.let { put(AudioSourceType.AMAZON, it) }
}

/**
 * The resolve request of one queue occurrence: its match target, its authoritative catalog id, the recording's ISRC when
 * the provider gave one, and the stored qualities.
 */
fun ContentOccurrence.toAudioResolveRequest(preferences: PreferenceSnapshot?): AudioResolveRequest {
    val canonical = item.canonicalSong()
    return AudioResolveRequest(
    mediaId = canonical.ref.id,
    target = TrackMatching.Target(
        title = canonical.title,
        artists = canonical.artists.map { it.name },
        album = canonical.album,
        durationMs = canonical.durationMs,
    ),
    directIds = item.ref.directSourceId()?.let { mapOf(it) }.orEmpty(),
    isrc = item.isrc?.takeIf { it.isNotBlank() },
    qualities = hostSourceQualities(preferences),
)
}

/**
 * The per-source catalogue ids the source chooser stored for one song, added to the request's authoritative ids. A
 * "play from" pin is not an id: it is applied to the chain by the caller through [SongSourceOverride].
 */
fun AudioResolveRequest.withSourcePins(preferences: PreferenceSnapshot?, songId: String): AudioResolveRequest {
    val qobuzTrack = preferences?.get(SongSourceQobuzTrackIdKey)?.let { SongSourceQobuzTrackId.get(it, songId) }
    val backupVideo = preferences?.get(SongSourceQobuzBackupVideoIdKey)?.let { SongSourceQobuzBackupVideoId.get(it, songId) }
    val extras = listOfNotNull(
        qobuzTrack?.let { AudioSourceType.QOBUZ to it },
        backupVideo?.let { AudioSourceType.QOBUZ_BACKUP to it },
    )
    val amazon = if (SongSourceOverride.get(preferences?.get(SongSourceOverrideKey), songId) == AudioSourceType.AMAZON) {
        SongSourceAmazonPick.get(preferences?.get(SongSourceAmazonPickKey), songId)
    } else null
    return if (extras.isEmpty() && amazon == null) this else copy(directIds = directIds + extras.toMap(), amazonPick = amazon)
}

/** A catalog id is authoritative only for the source that issued it. */
private fun ContentRef.directSourceId(): Pair<AudioSourceType, String>? =
    when (provider) {
        "youtube" -> AudioSourceType.YOUTUBE to id
        "tidal" -> AudioSourceType.TIDAL to id
        "deezer" -> AudioSourceType.DEEZER to id
        "jiosaavn" -> AudioSourceType.JIOSAAVN to id
        "qq" -> AudioSourceType.QQ to id
        "qobuz" -> AudioSourceType.QOBUZ to id
        "qobuz_backup" -> AudioSourceType.QOBUZ_BACKUP to id
        "apple" -> AudioSourceType.APPLE to id
        "spotify", "spotify_streaming" -> AudioSourceType.SPOTIFY to id
        else -> null
    }

/** The failure line of an occurrence no connected source resolved, naming each attempt and its result. */
fun unresolvedSourceReason(occurrence: ContentOccurrence, attempts: List<AudioAttempt>, host: String): String {
    val tried = attempts.joinToString(separator = ", ") { "${it.source.name} ${it.result.name}" }
    return "no connected source resolved ${occurrence.item.ref.provider} on this $host host" +
        if (tried.isEmpty()) "" else " ($tried)"
}
