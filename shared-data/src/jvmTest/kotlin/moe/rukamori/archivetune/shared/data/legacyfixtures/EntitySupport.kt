package moe.rukamori.archivetune.db.entities

import java.time.LocalDateTime

// Test-only signatures for unused MediaMetadata conversion methods. Not serialization substitutes.
data class SongEntity(val id: String, val title: String, val duration: Int, val thumbnailUrl: String?,
    val albumId: String?, val albumName: String?, val explicit: Boolean, val isMusicVideo: Boolean,
    val liked: Boolean, val likedDate: LocalDateTime?, val inLibrary: LocalDateTime?)
data class ArtistEntity(val id: String?, val name: String, val thumbnailUrl: String?)
data class AlbumEntity(val id: String, val title: String)
data class Song(
    val song: SongEntity, val artists: List<ArtistEntity>, val album: AlbumEntity?,
    val metadataOverride: SongMetadataOverrideEntity? = null,
)
