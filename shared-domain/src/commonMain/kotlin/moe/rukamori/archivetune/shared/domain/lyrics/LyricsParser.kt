package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * Entry point for lyric documents. [parse] reads QRC, LRC, enhanced LRC and YRC; TTML is read by
 * [parseTtml], as on Android, where the caller routes on [LyricsDetection.isTtml].
 */
object LyricsParser {
    fun parse(lyrics: String): List<LyricsLine> {
        val normalizedLyrics = LyricsNormalizer.normalize(lyrics)
        if (QrcParser.isQrc(normalizedLyrics)) {
            val translationsByStartMs = QrcParser.extractTranslations(normalizedLyrics)
            return QrcParser.parseQrc(normalizedLyrics).map { line ->
                val startMs = (line.startTime * 1000.0).toLong()
                LyricsDraft(
                    time = startMs,
                    text = line.text,
                    words =
                        line.words
                            .map { word -> LyricsWord(text = word.text, startTime = word.startTime, endTime = word.endTime) }
                            .takeIf { it.isNotEmpty() },
                    agent = line.agent,
                    providerTranslationText = translationsByStartMs[startMs],
                    durationMs = ((line.endTime - line.startTime) * 1000.0).toLong().coerceAtLeast(0L),
                ).toLine()
            }
        }

        val result = mutableListOf<LyricsDraft>()
        for (line in normalizedLyrics.lines()) {
            val entries = LrcParser.parseLineSyncedLrcLine(line) ?: LrcParser.parseMillisecondsSyncedLine(line)
            if (entries != null) result.addAll(entries)
        }
        return EnhancedLrcWords
            .clampLastWordEnds(LrcParser.mergeLineSyncedTranslations(result).sortedBy { it.time })
            .map { it.toLine() }
    }

    fun parseTtml(lyrics: String): List<LyricsLine> {
        val parsedLines = TtmlParser.parseTtml(LyricsNormalizer.normalize(lyrics))
        return ttmlLines(parsedLines)
    }

    fun readTtmlDocument(lyrics: String): TtmlDocument {
        val (lines, credits) = TtmlParser.readDocument(LyricsNormalizer.normalize(lyrics))
        return TtmlDocument(ttmlLines(lines), credits)
    }

    private fun ttmlLines(parsedLines: List<TtmlParser.ParsedLine>): List<LyricsLine> =
        parsedLines
            .map { line ->
                val words =
                    line.words
                        .filter { it.text.isNotEmpty() }
                        .map { word ->
                            LyricsWord(
                                text = word.text,
                                startTime = word.startTime,
                                endTime = word.endTime,
                                isBackground = word.isBackground,
                            )
                        }.takeIf { it.isNotEmpty() }

                LyricsDraft(
                    time = (line.startTime * 1000.0).toLong(),
                    text = line.text,
                    words = words,
                    agent = line.agent,
                    providerRomanizedText = line.providerRomanizedText,
                    providerRomanizedWords = line.providerRomanizedWords,
                    providerRomanizedLanguage = line.providerRomanizedLanguage,
                    providerTranslationText = line.providerTranslationText,
                )
            }.sortedBy { it.time }
            .map { it.toLine() }

    /** The document-level credits of a TTML document, or an empty value for non-TTML and malformed input. */
    fun parseTtmlCredits(lyrics: String): LyricsCredits =
        TtmlParser.parseCredits(LyricsNormalizer.normalize(lyrics))
}
