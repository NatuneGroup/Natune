package moe.rukamori.archivetune.shared.data.lyrics

/** One `/api/search` query pair: the title and the artist exactly as they are sent. */
internal data class LrcLibQuery(val title: String, val artist: String)

/**
 * The queries one lookup may send, in order: the caller's pair first, then one retry with the decorations YouTube
 * titles carry removed. The exact query is never skipped, so a title that already resolves keeps resolving the same
 * way; the retry only runs when the exact answer cannot serve the request at all.
 */
internal fun lrcLibQueries(
    title: String,
    artist: String,
): List<LrcLibQuery> =
    listOf(LrcLibQuery(title.trim(), artist.trim()), LrcLibQuery(cleanLrcLibTitle(title), cleanLrcLibArtist(artist)))
        .filter { query -> query.title.isNotBlank() }
        .distinctBy { query -> query.title.lowercase() to query.artist.lowercase() }

private val DASHED_DECORATION =
    Regex("""\s*[-–—]\s*(?:feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE)

private val BRACKET_GROUP = Regex("""[(\[{]([^)\]}]*)[])}]""")

/** Markers that make a bracketed group a featured-artist credit rather than part of the title. */
private val FEATURED_GROUP =
    Regex("""^(?:feat|ft|featuring|with|prod)\b\.?""", RegexOption.IGNORE_CASE)

/** Release noise a music video title carries; none of it names the recording. */
private val NOISE_GROUP =
    Regex(
        """^(?:official|lyrics?|lyric|audio|video|visuali[sz]er|mv|hd|hq|4k|8k|explicit|clean|remaster(?:ed)?)\b""",
        RegexOption.IGNORE_CASE,
    )

/**
 * The recording title behind a music-service title: "Song (feat. X)", "Song [ft. X]" and "Song - feat. X" all
 * reduce to "Song". Parentheticals that name the recording itself, such as "(Live)" or "(Acoustic)", stay: they
 * identify a different version and stripping them would match the studio take instead.
 */
internal fun cleanLrcLibTitle(title: String): String {
    var working = title.trim()
    working = BRACKET_GROUP.replace(working) { match ->
        val inner = match.groupValues[1].trim()
        if (FEATURED_GROUP.containsMatchIn(inner) || NOISE_GROUP.containsMatchIn(inner)) "" else match.value
    }
    working = DASHED_DECORATION.find(working)?.let { match -> working.substring(0, match.range.first) } ?: working
    working = working.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '–', '—', '|', '~', ',').trim()
    return working.ifEmpty { title.trim() }
}

private val FEATURED_ARTIST =
    Regex("""\s+(?:feat|ft|featuring|with)\b\.?\s+.*$""", RegexOption.IGNORE_CASE)
private val ARTIST_SEPARATOR = Regex("""\s*[,;&]\s*""")

/**
 * The primary artist of a decorated credit: "A, B", "A & B" and "A feat. B" all reduce to "A". LRCLIB files a
 * recording under one artist, so a multi-artist credit only ever matches its first name.
 */
internal fun cleanLrcLibArtist(artist: String): String {
    val first = artist.trim().split(ARTIST_SEPARATOR, limit = 2).first().trim()
    val primary = FEATURED_ARTIST.find(first)?.let { match -> first.substring(0, match.range.first) } ?: first
    return primary.trim().ifEmpty { artist.trim() }
}
