/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.lyrics_clear_selection
import moe.rukamori.archivetune.shared.ui.resources.lyrics_selected_count
import moe.rukamori.archivetune.shared.ui.resources.lyrics_share
import org.jetbrains.compose.resources.stringResource

/** Horizontal inset of each lyric row. The shaping width is the list width minus twice this inset. */
internal val LYRICS_ROW_PADDING = 16.dp

private const val LINE_HEIGHT_FACTOR = 1.25f
private const val PHONETIC_SIZE_RATIO = 0.55f
private const val SELECTED_ROW_ALPHA = 0.16f

/** One lyric row: the swept line with its reading and translation beneath. Tap and long press are host-decided. */
@Composable
internal fun LyricsRowItem(
    row: LyricsRenderRow,
    alignment: LyricsLineAlignment,
    active: Boolean,
    selected: Boolean,
    mainStyle: TextStyle,
    phoneticStyle: TextStyle,
    palette: LyricsPalette,
    clock: LyricsClock,
    shapes: LyricsShapeCache<LyricsShapeKey, LyricsShapedRow>,
    minSweepMs: Long,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Unsynced text has no timing to follow, so it reads at full strength rather than as an idle line.
    val linePalette = if (row.isSynced) palette else palette.copy(dim = palette.bright)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(lyricsRowTag(row.index))
            .background(if (selected) palette.glow.copy(alpha = SELECTED_ROW_ALPHA) else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment.horizontal(),
    ) {
        SweptLyricLine(
            row = row,
            style = mainStyle,
            palette = linePalette,
            active = active,
            clock = clock,
            shapes = shapes,
            minSweepMs = minSweepMs,
            reduceMotion = reduceMotion,
            modifier = Modifier.fillMaxWidth(),
        )
        row.phonetic?.let { Text(text = it, style = phoneticStyle, color = palette.dim) }
        row.translation?.let { Text(text = it, style = phoneticStyle, color = palette.dim) }
    }
}

/** The bar shown while lines are selected. It holds the count and the share and clear actions. */
@Composable
internal fun LyricsSelectionBar(
    count: Int,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.lyrics_selected_count, count),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row {
            TextButton(onClick = onClear) { Text(stringResource(Res.string.lyrics_clear_selection)) }
            TextButton(onClick = onShare) { Text(stringResource(Res.string.lyrics_share)) }
        }
    }
}

/** The stable test tag of one lyric row, so tests can find it by index. */
internal fun lyricsRowTag(index: Int): String = "lyrics-row-$index"

/** The stable test tag of the fullscreen lyric list, so tests can scroll it. */
internal const val LYRICS_LIST_TAG = "lyrics-list"

@Composable
internal fun lyricsPalette(profile: LyricsModeProfile): LyricsPalette {
    val colors = MaterialTheme.colorScheme
    return remember(profile, colors) {
        LyricsPalette(
            dim = colors.onSurface.copy(alpha = profile.idleAlpha),
            bright = colors.onSurface,
            glow = colors.primary,
        )
    }
}

internal fun lyricsMainStyle(profile: LyricsModeProfile, alignment: LyricsLineAlignment): TextStyle =
    TextStyle(
        fontSize = profile.mainSizeSp.sp,
        lineHeight = (profile.mainSizeSp * profile.lineSpacingFactor * LINE_HEIGHT_FACTOR).sp,
        fontWeight = FontWeight.Bold,
        textAlign = alignment.textAlign(),
    )

/** The reading and translation rows sit at 0.55 of the main size, as the Android renderers set them. */
internal fun lyricsPhoneticStyle(profile: LyricsModeProfile, alignment: LyricsLineAlignment): TextStyle =
    TextStyle(fontSize = (profile.mainSizeSp * PHONETIC_SIZE_RATIO).sp, textAlign = alignment.textAlign())

/**
 * The text styles of one look, one pair per alignment. A row is shaped in the style it is drawn with, so the shape
 * cache key and the drawn text always agree.
 */
internal class LyricsRowStyles(profile: LyricsModeProfile) {
    private val main = LyricsLineAlignment.entries.associateWith { lyricsMainStyle(profile, it) }
    private val phonetic = LyricsLineAlignment.entries.associateWith { lyricsPhoneticStyle(profile, it) }

    fun main(alignment: LyricsLineAlignment): TextStyle = main.getValue(alignment)

    fun phonetic(alignment: LyricsLineAlignment): TextStyle = phonetic.getValue(alignment)
}

private fun LyricsLineAlignment.textAlign(): TextAlign =
    when (this) {
        LyricsLineAlignment.START -> TextAlign.Start
        LyricsLineAlignment.CENTER -> TextAlign.Center
        LyricsLineAlignment.END -> TextAlign.End
    }

private fun LyricsLineAlignment.horizontal(): Alignment.Horizontal =
    when (this) {
        LyricsLineAlignment.START -> Alignment.Start
        LyricsLineAlignment.CENTER -> Alignment.CenterHorizontally
        LyricsLineAlignment.END -> Alignment.End
    }
