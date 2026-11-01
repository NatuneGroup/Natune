package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * The `[translatedLyrics]` trailing section of a line-synced LRC document. A provider or translator
 * hands the app plain translated text per line; storing it raw would throw the source text and every
 * timestamp away. [mergeTranslationIntoLrc] re-stamps each translated line with the source row's own
 * timestamp and appends it under a `[translatedLyrics]` header, which [LrcParser.mergeLineSyncedTranslations]
 * then folds back into `providerTranslationText` on the next parse. A previous section is stripped
 * first, so re-translating replaces rather than accumulates.
 */
object LrcTranslationMerge {
    private val SECTION_HEADER = Regex("""\[(translatedLyrics|translated_lyrics)]""", RegexOption.IGNORE_CASE)

    /**
     * The source document with its prior translation section removed and [translatedLines] appended
     * as a fresh `[translatedLyrics]` section. Lines already carrying an LRC stamp are kept verbatim;
     * plain lines are stamped with the timestamp of the source row at the same index. Rows beyond the
     * source's line count are kept unstamped so no translation text is silently dropped.
     *
     * Returns null when [sourceLrc] holds no line-synced rows, letting the caller keep its plain-text
     * behaviour instead of writing an empty section into an unsynced document.
     */
    fun mergeTranslationIntoLrc(sourceLrc: String, translatedLines: List<String>): String? {
        val keptLines = mutableListOf<String>()
        val rowTimes = mutableListOf<Long>()
        for (raw in sourceLrc.lines()) {
            val line = raw.trimEnd()
            if (SECTION_HEADER.containsMatchIn(line.trim())) break // the section is trailing; drop it onward
            val drafts = LrcParser.parseLineSyncedLrcLine(line)
            if (drafts != null && drafts.isNotEmpty()) {
                rowTimes.add(drafts.first().time)
            } else {
                keptLines.add(line)
                continue
            }
            keptLines.add(line)
        }
        if (rowTimes.isEmpty()) return null

        val out = StringBuilder(keptLines.joinToString("\n").trimEnd())
        out.append('\n').append("[translatedLyrics]").append('\n')
        translatedLines.forEachIndexed { index, text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (LrcParser.LINE_REGEX.containsMatchIn(trimmed)) {
                out.append(trimmed).append('\n')
            } else if (index < rowTimes.size) {
                out.append(formatLrcTimestamp(rowTimes[index])).append(trimmed).append('\n')
            } else {
                out.append(trimmed).append('\n')
            }
        }
        return out.toString().trimEnd()
    }

    /**
     * The document to persist for a translation result: [translatedResult] verbatim when it already
     * carries both languages — a `[translatedLyrics]` section, or a translator rebuild that kept the
     * source rows (interleaved pairs repeat the source row at its own timestamp) — and the merged
     * source+section otherwise. Merging a bilingual result again would duplicate every sung row
     * under a fresh translation header.
     */
    fun saveableTranslation(sourceLrc: String?, translatedResult: String): String {
        if (alreadyBilingual(translatedResult, sourceLrc)) return translatedResult
        return sourceLrc
            ?.let { mergeTranslationIntoLrc(it, translatedResult.lines()) }
            ?: translatedResult
    }

    /**
     * True when [result] already holds a translation section or reuses at least one synced row of
     * [sourceLrc], the signature of a rebuilt bilingual document. A plain translation reusing one
     * source row verbatim (timestamp and text) still counts: re-stamping it could only corrupt it.
     */
    private fun alreadyBilingual(result: String, sourceLrc: String?): Boolean {
        if (SECTION_HEADER.containsMatchIn(result)) return true
        if (sourceLrc == null) return false
        val sourceRows =
            sourceLrc
                .lineSequence()
                .mapNotNull(LrcParser::parseLineSyncedLrcLine)
                .flatten()
                .mapTo(mutableSetOf()) { it.time to it.text }
        if (sourceRows.isEmpty()) return false
        return result
            .lineSequence()
            .mapNotNull(LrcParser::parseLineSyncedLrcLine)
            .flatten()
            .any { (it.time to it.text) in sourceRows }
    }

    /** `mm:ss.xx`, the centisecond stamp the LRC parser accepts. */
    internal fun formatLrcTimestamp(timeMs: Long): String {
        val total = timeMs.coerceAtLeast(0L)
        val minutes = total / 60_000
        val seconds = total / 1_000 % 60
        val centis = total % 1_000 / 10
        return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}." +
            "${centis.toString().padStart(2, '0')}]"
    }
}
