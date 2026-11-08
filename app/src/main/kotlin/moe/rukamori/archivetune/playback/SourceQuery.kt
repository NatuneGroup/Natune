package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.audiosource.AmazonTrackPick

/** Metadata used to look a track up across the configured audio sources. */
internal data class SourceQuery(
    val mediaId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    /**
     * ISRC of the wanted recording, when the queue item carried one (catalogue imports only).
     * A source that can look up by ISRC uses it for an exact match and skips its text search.
     */
    val isrc: String? = null,
    /**
     * When non-null, the Qobuz resolver skips its title/artist search and
     * downloads this exact trackId. Set when the user picks a specific
     * Qobuz track from the "Play from" source-search popup — the mediaId
     * encodes the trackId as "qobuz:{trackId}" and [resolveMultiSourceDataSpec]
     * extracts it into this field.
     */
    val directQobuzTrackId: String? = null,
    /**
     * When non-null, the Qobuz-backup resolver asks the mirror for THIS video id
     * instead of [mediaId]. Set when the user picks a specific row in the
     * "Play from" search popup — the mirror is keyed by YouTube video id, and the
     * row they chose is a different catalogue entry from the song that is playing.
     * Keeping [mediaId] untouched is what makes the switch audio-only.
     */
    val directQobuzBackupVideoId: String? = null,
    /**
     * The Amazon row the user tapped in the "Play from" search popup. Amazon instances only
     * resolve by metadata, so the Amazon resolver searches with this instead of the song's
     * own title/artist and gates the result against it.
     */
    val amazonPick: AmazonTrackPick? = null,
    /**
     * The Spotify track id the queue item came from (catalogue imports only). The Spotify
     * streaming source plays this exact track instead of searching for one.
     */
    val spotifyTrackId: String? = null,
)
