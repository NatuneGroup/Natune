package moe.rukamori.archivetune.shared.domain.playback

/** A local/non-YouTube item never leaves the device for a segment lookup, even with both toggles on. */
object SponsorBlockPlaybackPolicy {
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")

    fun allowsLookup(
        mediaId: String,
        enabled: Boolean,
        podcastsEnabled: Boolean,
        isPodcast: Boolean,
        isLocal: Boolean,
        isYouTube: Boolean = true,
    ): Boolean = enabled && !isLocal && isYouTube && videoId.matches(mediaId) && (!isPodcast || podcastsEnabled)
}
