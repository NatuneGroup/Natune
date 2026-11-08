package moe.rukamori.archivetune.ui.component

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry

internal const val ALTERNATE_MIN_KARAOKE_SYLLABLE_DURATION_MS = 1

internal fun buildAlternateLineSyncedLrcLine(
    entry: LyricsEntry,
    romanizedText: String?,
    start: Int,
    end: Int,
): ISyncedLine {
    val translation = providedTranslationTextForEntry(entry)
    val normalizedRomanizedText = romanizedText?.trim()?.takeIf { it.isNotEmpty() }

    if (normalizedRomanizedText == null) {
        return SyncedLine(
            content = entry.text,
            translation = translation,
            start = start,
            end = end,
        )
    }

    // Keep the existing wrapping units, but never manufacture timed phonetic alignment.
    val syllables = entry.text.toLyricsWrappingUnits().ifEmpty { listOf(entry.text) }.map { unit ->
        KaraokeSyllable(
            content = unit,
            start = start,
            end = start + ALTERNATE_MIN_KARAOKE_SYLLABLE_DURATION_MS,
            phonetic = null,
        )
    }

    return KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = translation,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
        phonetic = normalizedRomanizedText,
    )
}
