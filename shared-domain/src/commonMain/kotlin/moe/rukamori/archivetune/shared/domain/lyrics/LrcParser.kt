package moe.rukamori.archivetune.shared.domain.lyrics

/** Line-synced LRC rows, YRC millisecond rows, and the repeated-timestamp translation fold. */
internal object LrcParser {
    val LINE_REGEX = Regex("""((\[\d{1,3}:\d{2}(?:[.:]\d{2,3})?\]\s*)+)(.*)""")
    val TIME_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?\]""")
    val YRC_LINE_REGEX = Regex("""\[(\d{1,8}),\d{1,8}\](.*)""")
    private val YRC_WORD_TIME_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")

    /** One LRC row per timestamp; an inline-stamped row also yields word timings. */
    fun parseLineSyncedLrcLine(line: String): List<LyricsDraft>? {
        if (line.isEmpty()) return null
        val matchResult = LINE_REGEX.matchEntire(line.trim()) ?: return null
        val times = matchResult.groupValues[1]
        val rawText = matchResult.groupValues[3]
        val text = cleanInlineWordTimingText(rawText)
        val inlineWords = EnhancedLrcWords.extractWordTimestamps(rawText)

        return TIME_REGEX.findAll(times).map { timeMatch ->
            val minutes = timeMatch.groupValues[1].toLong()
            val seconds = timeMatch.groupValues[2].toLong()
            val fraction = timeMatch.groupValues[3]
            var millis = fraction.toLongOrNull() ?: 0L
            when (fraction.length) {
                1 -> millis *= 100
                2 -> millis *= 10
            }
            val time = minutes * MINUTE_MS + seconds * SECOND_MS + millis
            if (inlineWords != null) {
                // A repeated stamp reuses the same words: any word stamped before this row's own start
                // is pulled forward so the karaoke never highlights a word before its line begins.
                val lineStartSec = time / 1000.0
                val words =
                    inlineWords.map { word ->
                        if (word.startTime >= lineStartSec - 0.001) {
                            word
                        } else {
                            word.copy(
                                startTime = lineStartSec,
                                endTime = maxOf(word.endTime, lineStartSec + EnhancedLrcWords.MIN_WORD_DURATION_MS / 1000.0),
                            )
                        }
                    }
                LyricsDraft(time = time, text = text, words = words)
            } else {
                LyricsDraft(time = time, text = text)
            }
        }.toList()
    }

    /** YRC framing `[start,duration]text`: only the line start and cleaned text are read. */
    fun parseMillisecondsSyncedLine(line: String): List<LyricsDraft>? {
        if (line.isEmpty()) return null
        val matchResult = YRC_LINE_REGEX.matchEntire(line.trim()) ?: return null
        val time = matchResult.groupValues[1].toLongOrNull() ?: return null
        val text = cleanInlineWordTimingText(matchResult.groupValues[2])
        return listOf(LyricsDraft(time = time, text = text))
    }

    /**
     * Folds the second row of a translation-annotated line-synced document into
     * [LyricsDraft.providerTranslationText]. Such a document repeats a line's timestamp and puts the
     * translation on the following row.
     *
     * The fold is only valid when the first row at that timestamp is real text. A blank row is not the
     * original half of a translation pair: LRCLIB carries empty spacer rows, and folding one of those
     * left both rows invisible. Blank rows and any row beyond the single translation are kept.
     */
    fun mergeLineSyncedTranslations(entries: List<LyricsDraft>): List<LyricsDraft> {
        val rowsByTime = linkedMapOf<Long, MutableList<LyricsDraft>>()
        entries.forEach { entry -> rowsByTime.getOrPut(entry.time) { mutableListOf() }.add(entry) }

        val merged = mutableListOf<LyricsDraft>()
        rowsByTime.values.forEach { rows ->
            val original = rows.first()
            if (original.text.isBlank()) {
                merged.addAll(rows)
                return@forEach
            }

            var head = original
            val extras = mutableListOf<LyricsDraft>()
            rows.drop(1).forEach { row ->
                val translatedText =
                    row.text
                        .collapseLyricsWhitespace()
                        .trim()
                        .takeIf { it.isNotEmpty() && !it.equals(head.text.trim(), ignoreCase = true) }

                if (translatedText != null && head.providerTranslationText == null) {
                    head = head.copy(providerTranslationText = translatedText)
                } else {
                    extras.add(row)
                }
            }
            merged.add(head)
            merged.addAll(extras)
        }
        return merged
    }

    fun cleanInlineWordTimingText(text: String): String =
        text
            .replace(EnhancedLrcWords.WORD_TIME_REGEX, "")
            .replace(EnhancedLrcWords.INLINE_MILLISECONDS_TIME_REGEX, "")
            .replace(YRC_WORD_TIME_REGEX, "")
            .collapseLyricsWhitespace()
            .trimLyricsSpace()
}
