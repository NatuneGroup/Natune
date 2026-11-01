package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.lyrics_auto_created
import moe.rukamori.archivetune.shared.ui.resources.lyrics_written_by
import org.jetbrains.compose.resources.stringResource

/** The mark a machine-made reading or translation carries. */
private const val AUTO_CREATED_MARK = "✦ "

/**
 * The credits block at the end of a fullscreen list: the songwriters the document names, and the
 * badge a machine-made translation or transliteration earns, both dim and small under the last line.
 */
@Composable
internal fun LyricsCreditsFooter(
    credits: LyricsCredits,
    style: TextStyle,
    palette: LyricsPalette,
    modifier: Modifier = Modifier,
) {
    if (credits.isEmpty) return
    val autoCreated = credits.translationAutoCreated || credits.transliterationAutoCreated
    Column(
        modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (credits.songwriters.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.lyrics_written_by, credits.songwriters.joinToString(", ")),
                style = style,
                color = palette.dim,
                textAlign = TextAlign.Center,
            )
        }
        if (autoCreated) {
            Text(
                text = AUTO_CREATED_MARK + stringResource(Res.string.lyrics_auto_created),
                style = style,
                color = palette.dim.copy(alpha = palette.dim.alpha * 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
