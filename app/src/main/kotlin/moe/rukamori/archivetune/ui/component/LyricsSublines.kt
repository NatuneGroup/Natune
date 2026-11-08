package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable

private const val TRANSLATION_REVEAL_MS = 600

/**
 * A syllable line's text with the character range each syllable occupies in it. Joining is verbatim: only
 * the whitespace the source put in each [KaraokeSyllable.content] survives, so syllable tokens of ONE
 * word ("a" + "gain", "mo" + "ment") stay one word instead of being split by an invented gap. The karaoke
 * sweep clips against these ranges, so two syllables with no whitespace between them are adjacent slices
 * and the sweep crosses such a word continuously. One shared join keeps every renderer's text and boxes
 * identical.
 */
internal class JoinedSyllables(
    val text: String,
    val ranges: List<IntRange?>,
)

/** Joins syllable content verbatim, keeping the range each syllable occupies for the per-syllable sweep. */
internal fun List<KaraokeSyllable>.joinSyllables(): JoinedSyllables {
    val builder = StringBuilder()
    val ranges = ArrayList<IntRange?>(size)
    for (syllable in this) {
        val first = builder.length
        builder.append(syllable.content)
        ranges += if (builder.length > first) first until builder.length else null
    }
    return JoinedSyllables(builder.toString(), ranges)
}

/** The plain text of a syllable line. Both the plain [Text] and the swept line read [joinSyllables]. */
internal fun List<KaraokeSyllable>.joinSyllableContents(): String = joinSyllables().text

/**
 * Word-timed karaoke sweep: each syllable's colour interpolates from the dimmed rest colour to the
 * full text colour across its own [start, end], over the verbatim text so the sweep never invents a gap.
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
    val joined = remember(syllables) { syllables.joinSyllables() }
    val annotated =
        buildAnnotatedString {
            append(joined.text)
            syllables.forEachIndexed { index, syllable ->
                val range = joined.ranges[index] ?: return@forEachIndexed
                val span = (syllable.end - syllable.start).coerceAtLeast(1)
                val progress = ((position - syllable.start).toFloat() / span).coerceIn(0f, 1f)
                addStyle(
                    SpanStyle(color = lerp(unsungColor, textColor, progress)),
                    range.first,
                    range.last + 1,
                )
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
    /** The translation slides in when its line activates and stays once revealed. */
    isActive: Boolean = true,
    reduceMotion: Boolean = false,
) {
    if (translation.isNullOrBlank()) return
    // A republished translation appears in place; only an activation slides it in.
    val reveal = remember(translation) { MutableTransitionState(isActive) }
    LaunchedEffect(isActive, translation) {
        if (isActive) reveal.targetState = true
    }
    AnimatedVisibility(
        visibleState = reveal,
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            expandVertically(expandFrom = Alignment.Top, animationSpec = tween(TRANSLATION_REVEAL_MS)) +
                fadeIn(animationSpec = tween(TRANSLATION_REVEAL_MS))
        },
    ) {
        Column {
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
    }
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
