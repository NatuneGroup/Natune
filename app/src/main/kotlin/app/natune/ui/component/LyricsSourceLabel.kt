package app.natune.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.ui.composable.list.LyricsLazyListState

// The lyrics source line ("Lyrics from …"), drawn above the first line of the enhanced lyrics views.

private const val LYRICS_SOURCE_LABEL_ALPHA = 0.6f
private val LyricsSourceLabelGap = 12.dp

@Composable
internal fun KaraokeLyricsSourceLabel(
    text: String,
    textColor: Color,
    listState: LyricsLazyListState,
    anchorOffset: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val anchorPx = with(density) { anchorOffset.toPx() }
    val gapPx = with(density) { LyricsSourceLabelGap.toPx() }

    LyricsSourceLabelText(
        text = text,
        textColor = textColor,
        modifier =
            modifier
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    if (listState.firstVisibleItemIndex != 0) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val firstLineTop = anchorPx - listState.firstVisibleItemScrollOffset
                    val labelTop = firstLineTop - gapPx - size.height
                    translationY = labelTop
                    alpha = ((labelTop + size.height) / size.height).coerceIn(0f, 1f)
                },
    )
}

@Composable
internal fun LyricsSourceLabelText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = textColor.copy(alpha = LYRICS_SOURCE_LABEL_ALPHA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}
