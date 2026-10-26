package moe.rukamori.archivetune.shared.domain.repository

/**
 * Builds the request for a YouTube watch-next (radio/mix) collection, the portable counterpart of the
 * Android `YouTubeQueue(WatchEndpoint(videoId, playlistId, playlistSetVideoId, params))`.
 *
 * A [ContentKind.SONG] seed is a `WatchEndpoint(videoId=...)` and follows the automix preview, as
 * `YouTubeQueue.radio` does. A [ContentKind.PLAYLIST] seed is an RD/mix endpoint such as an artist or
 * playlist radio and carries the endpoint's [params] through the section. [playlistSetVideoId] is the
 * endpoint's set id when the browse response supplies one. Any other kind cannot seed watch-next and is
 * refused rather than mapped to a different collection.
 */
fun radioQueueRequest(
    seed: ContentRef,
    playlistSetVideoId: String? = null,
    params: String? = null,
): ContentRequest {
    require(seed.provider == ContentProvider.YOUTUBE.id) { "Only YouTube content seeds a radio queue" }
    require(seed.kind == ContentKind.SONG || seed.kind == ContentKind.PLAYLIST) {
        "A radio queue is seeded by a song or playlist, not ${seed.kind}"
    }
    return ContentRequest(
        provider = ContentProvider.YOUTUBE,
        surface = ContentSurface.RADIO,
        parent = seed,
        section =
            if (playlistSetVideoId == null && params == null) {
                null
            } else {
                ContentSection(id = playlistSetVideoId ?: seed.id, params = params)
            },
    )
}
