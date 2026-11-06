package moe.rukamori.archivetune.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable


/**
 * Word-timed karaoke sweep: each syllable's colour interpolates from the dimmed rest colour to the
 * full text colour across its own [start, end].
 */
@Composable
internal fun KaraokeSweepText(
    syllables: List<KaraokeSyllable>,
    currentPosition: () -> Int,
    textColor: Color,
    textStyle: TextStyle,
) {
    val position = currentPosition()
    val unsungColor = textColor.copy(alpha = 0.45f)
    val annotated =
        buildAnnotatedString {
            syllables.forEachIndexed { index, syllable ->
                val span = (syllable.end - syllable.start).coerceAtLeast(1)
                val progress = ((position - syllable.start).toFloat() / span).coerceIn(0f, 1f)
                val color = lerp(unsungColor, textColor, progress)
                withStyle(SpanStyle(color = color)) {
                    append(syllable.content)
                    val next = syllables.getOrNull(index + 1)
                    if (next != null && needsLatinWordGap(syllable.content, next.content)) {
                        append(" ")
                    }
                }
            }
        }
    Text(text = annotated, style = textStyle)
}

/** The phonetic (romanisation) and translation rows stacked under the main line. */
@Composable
internal fun TranslationStack(
    translation: String?,
    textColor: Color,
    phoneticTextStyle: TextStyle,
) {
    if (translation.isNullOrBlank()) return
    translation
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { row ->
            Text(
                text = row,
                style = phoneticTextStyle,
                color = textColor.copy(alpha = 0.85f),
            )
        }
}

/** A separating space only between Latin-script words — CJK syllables join seamlessly. */
internal fun needsLatinWordGap(previous: String, next: String): Boolean {
    val prevTail = previous.lastOrNull() ?: return false
    val nextHead = next.firstOrNull() ?: return false
    if (!isLatinWordChar(nextHead) || prevTail.isWhitespace() || nextHead.isWhitespace()) return false
    return isLatinWordChar(prevTail) || prevTail in SENTENCE_PUNCTUATION
}

private const val SENTENCE_PUNCTUATION = ".,!?;:…"

internal fun isLatinWordChar(ch: Char): Boolean =
    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '\''

internal fun List<KaraokeSyllable>.joinSyllableContents(): String {
    val builder = StringBuilder()
    forEachIndexed { index, syllable ->
        builder.append(syllable.content)
        val next = getOrNull(index + 1)
        if (next != null && needsLatinWordGap(syllable.content, next.content)) {
            builder.append(' ')
        }
    }
    return builder.toString()
}

internal fun extractTtmlWriters(lyrics: String?): String {
    if (lyrics.isNullOrBlank()) return ""
    val writerTagPattern =
        Regex(
            pattern = "<(songwriter|composer|lyricist|writer|ar)(?:\\s[^>]*)?>([^<]+)</\\1>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    val writers = LinkedHashSet<String>()
    writerTagPattern.findAll(lyrics).forEach { match ->
        val name = match.groupValues.getOrNull(2)?.trim()
        if (!name.isNullOrBlank()) writers.add(name)
    }
    return writers.joinToString(", ").trim()
}
