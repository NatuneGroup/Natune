package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.app_name
import org.jetbrains.compose.resources.stringResource

private val SHARE_CARD_MAX_WIDTH = 340.dp

/**
 * The share card for the selected lines. It is drawn through [layer], so its pixels can be captured as an image
 * without a screenshot of the surrounding screen. Its width never exceeds [SHARE_CARD_MAX_WIDTH].
 */
@Composable
internal fun LyricsShareCard(
    rows: List<LyricsRenderRow>,
    layer: GraphicsLayer,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lineStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    val readingStyle = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center)
    Column(
        modifier
            .widthIn(max = SHARE_CARD_MAX_WIDTH)
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .background(colors.surfaceContainerHigh, RectangleShape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            Text(
                text = row.text,
                style = lineStyle,
                color = colors.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            row.phonetic?.let {
                Text(text = it, style = readingStyle, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            text = stringResource(Res.string.app_name),
            style = readingStyle,
            color = colors.onSurfaceVariant,
        )
    }
}
