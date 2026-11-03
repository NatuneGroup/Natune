package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.isLocalMediaId

/** Resolves reporting metadata without changing the canonical song or playback state. */
internal object PlaybackPresenceSong {
    fun resolve(
        dbSong: Song?,
        mediaMetadata: MediaMetadata?,
        durationMs: Long,
    ): Song? {
        val metadataSong = mediaMetadata?.let { createTransientSongFromMedia(it) }
        val song =
            when {
                dbSong == null -> metadataSong
                metadataSong == null -> dbSong
                else -> dbSong.withPresenceMetadata(metadataSong)
            }

        return song?.withEffectiveNames().withResolvedPresenceDuration(durationMs)
    }

    private fun Song.withPresenceMetadata(metadataSong: Song): Song {
        val resolvedArtists =
            metadataSong.artists.takeIf { metadataArtists ->
                metadataArtists.any { it.hasRemotePresenceId() }
            } ?: artists

        return copy(
            song =
                song.copy(
                    thumbnailUrl = song.thumbnailUrl ?: metadataSong.song.thumbnailUrl,
                    albumId = song.albumId ?: metadataSong.song.albumId,
                    albumName = song.albumName ?: metadataSong.song.albumName,
                ),
            artists = resolvedArtists,
            album = album ?: metadataSong.album,
        )
    }

    private fun Song?.withResolvedPresenceDuration(durationMs: Long): Song? {
        val song = this ?: return null
        if (song.song.duration > 0 || durationMs <= 0) return song
        val durationSeconds =
            (durationMs / 1000L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return song.copy(song = song.song.copy(duration = durationSeconds))
    }

    private fun ArtistEntity.hasRemotePresenceId(): Boolean = channelId.isRemotePresenceId() || id.isRemotePresenceId()

    private fun String?.isRemotePresenceId(): Boolean {
        val id = this?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return !id.isLocalMediaId() &&
            !id.isTelegramMediaId() &&
            !id.startsWith("LOCAL_ARTIST_") &&
            !id.startsWith("LA") &&
            !id.contains("privately_owned_artist", ignoreCase = true)
    }

    // Create a transient Song object from current Player MediaMetadata when the DB doesn't have it.
    private fun createTransientSongFromMedia(media: MediaMetadata): Song {
        val songEntity =
            SongEntity(
                id = media.id,
                title = media.title,
                duration = media.duration,
                thumbnailUrl = media.thumbnailUrl,
                albumId = media.album?.id,
                albumName = media.album?.title,
                explicit = media.explicit,
                isMusicVideo = media.isMusicVideo,
                isLocal = media.id.isLocalMediaId(),
            )

        val artists =
            media.artists.map { artist ->
                ArtistEntity(
                    id = artist.id ?: "LA_unknown_${artist.name}",
                    name = artist.name,
                    thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
                    isLocal = artist.id == null || artist.id.isLocalMediaId(),
                )
            }

        val album =
            media.album?.let { alb ->
                AlbumEntity(
                    id = alb.id,
                    playlistId = null,
                    title = alb.title,
                    year = null,
                    thumbnailUrl = media.thumbnailUrl,
                    themeColor = null,
                    songCount = 1,
                    duration = media.duration,
                    isLocal = media.id.isLocalMediaId(),
                )
            }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }
}
