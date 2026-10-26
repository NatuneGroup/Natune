package moe.rukamori.archivetune.shared.domain.lastfm

/**
 * The fields Last.fm's track.* write methods take, cleaned of YouTube-specific noise. [from] is the only way the
 * scrobble path builds one, so every write goes through the same cleanup.
 */
data class LastFmTrackMetadata(
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Int?,
) {
    companion object {
        private val TOPIC_CHANNEL_SUFFIX = Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE)

        // Only bracketed upload-format labels are removed. "(Live)", "(Remix)" and "(feat. X)" name a different
        // recording and must reach Last.fm unchanged.
        private val UPLOAD_LABEL_SUFFIX =
            Regex(
                """\s*[(\[]\s*(?:official\s+)?""" +
                    """(?:music\s+video|video|audio|lyrics?(?:\s+video)?|visuali[sz]er|mv|m/v|hd|hq|4k)\s*[)\]]""",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Last.fm takes one artist string and matches it against its catalogue, so a joined "A, B" list creates a
         * phantom artist that never links to either. The first non-blank name is the only value that resolves.
         * Returns null when Last.fm would not accept an artist or a title.
         */
        fun from(
            artistNames: List<String>,
            title: String,
            albumTitle: String?,
            durationSeconds: Int?,
        ): LastFmTrackMetadata? {
            val artist =
                artistNames
                    .asSequence()
                    .map { it.replace(TOPIC_CHANNEL_SUFFIX, "").trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: return null
            val rawTitle = title.trim()
            val track = rawTitle.replace(UPLOAD_LABEL_SUFFIX, "").trim().ifEmpty { rawTitle }
            if (track.isEmpty()) return null
            return LastFmTrackMetadata(
                artist = artist,
                track = track,
                album = albumTitle?.trim()?.takeIf { it.isNotEmpty() },
                durationSeconds = durationSeconds?.takeIf { it > 0 },
            )
        }
    }
}

/**
 * Last.fm's scrobble rule: a track longer than [minSongDurationSeconds] scrobbles once it has been heard for
 * [delayPercent] of its length or [delaySeconds], whichever comes first. The defaults are the retained baseline values.
 */
data class LastFmScrobbleTiming(
    val minSongDurationSeconds: Int = 30,
    val delayPercent: Float = 0.5f,
    val delaySeconds: Int = 180,
) {
    /**
     * Listening time that must accumulate before the play counts, or null when the length is unknown or not longer
     * than the minimum. An unknown length cannot be judged, so it is never scrobbled.
     */
    fun thresholdMillis(durationSeconds: Int): Long? {
        if (durationSeconds <= 0 || durationSeconds <= minSongDurationSeconds) return null
        val byPercent = (durationSeconds * 1000L * delayPercent).toLong()
        return minOf(byPercent, delaySeconds * 1000L)
    }
}
