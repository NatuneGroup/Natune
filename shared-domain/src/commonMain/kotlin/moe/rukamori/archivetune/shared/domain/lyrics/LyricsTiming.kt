package moe.rukamori.archivetune.shared.domain.lyrics

/** Which lyric row is current at a playback position, and where instrumental breaks belong. */
object LyricsTiming {
    private const val INSTRUMENTAL_GAP_THRESHOLD_MS = 5_000L
    private const val INSTRUMENTAL_INTRO_START_MS = 1_000L
    private const val INSTRUMENTAL_OUTRO_VOCAL_TAIL_MS = 2_500L

    /** Back-to-back lines get the lead; a longer gap between line starts transitions exactly on time. */
    private const val LEAD_GAP_LIMIT_MS = 2_000L

    /**
     * Index of the current row for [positionMs], or -1 for an empty list. A row with an explicit
     * duration holds until that duration ends. Otherwise the next row starts [leadMs] early, but only
     * when the two rows are back-to-back.
     */
    fun findCurrentLineIndex(
        lines: List<LyricsLine>,
        positionMs: Long,
        leadMs: Long = 300L,
    ): Int {
        if (lines.isEmpty()) return -1

        // Last line whose start is strictly before the position (no lead); the lead is decided below.
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (lines[mid].time < positionMs) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val currentIdx = high.coerceIn(0, lines.lastIndex)

        val nextIdx = currentIdx + 1
        if (nextIdx > lines.lastIndex) return currentIdx

        val currentLine = lines[currentIdx]
        val nextLine = lines[nextIdx]

        // A line with an explicit duration (QRC) must not hand over to the next line before it ends.
        if (currentLine.durationMs > 0L) {
            val currentLineEndMs = currentLine.time + currentLine.durationMs
            if (positionMs < currentLineEndMs) return currentIdx
        }

        val gapBetweenLineStartsMs = nextLine.time - currentLine.time
        val effectiveLeadMs = if (gapBetweenLineStartsMs > LEAD_GAP_LIMIT_MS) 0L else leadMs

        return if (positionMs + effectiveLeadMs >= nextLine.time) nextIdx else currentIdx
    }

    /**
     * Adds a leading instrumental row when the first sung line starts late, and a trailing one when the
     * song runs well past the last sung line. [songDurationMs] of 0 disables the trailing row.
     */
    fun insertInstrumentalBreaks(
        lines: List<LyricsLine>,
        songDurationMs: Long = 0L,
    ): List<LyricsLine> {
        if (lines.isEmpty()) return lines
        val result = mutableListOf<LyricsLine>()
        introBreak(lines)?.let { result.add(it) }
        result.addAll(lines)
        outroBreak(lines, songDurationMs)?.let { result.add(it) }
        return result
    }

    private fun introBreak(lines: List<LyricsLine>): LyricsLine? {
        val firstTimedVocal = lines.firstOrNull { it.time >= 0L && it.text.isNotBlank() } ?: return null
        val introGapMs = firstTimedVocal.time - INSTRUMENTAL_INTRO_START_MS
        if (introGapMs < INSTRUMENTAL_GAP_THRESHOLD_MS) return null
        return instrumentalBreak(time = INSTRUMENTAL_INTRO_START_MS, durationMs = introGapMs)
    }

    private fun outroBreak(
        lines: List<LyricsLine>,
        songDurationMs: Long,
    ): LyricsLine? {
        if (songDurationMs <= 0L) return null
        val lastVocal = lines.lastOrNull { it.text.isNotBlank() } ?: return null
        val outroStartMs = lastVocal.time + INSTRUMENTAL_OUTRO_VOCAL_TAIL_MS
        val outroDurationMs = songDurationMs - outroStartMs
        if (outroDurationMs < INSTRUMENTAL_GAP_THRESHOLD_MS) return null
        return instrumentalBreak(time = outroStartMs, durationMs = outroDurationMs)
    }

    private fun instrumentalBreak(
        time: Long,
        durationMs: Long,
    ): LyricsLine =
        SyncedLine(
            time = time,
            text = "",
            isInstrumental = true,
            durationMs = durationMs,
        )
}
