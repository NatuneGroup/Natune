/*
 * The stream-details sheet in the layout of Specty's track analysis (RoTatsuLabs/Specty, GPL-3.0): format chips,
 * a verdict card that says what the stream is before any numbers, three large metric tiles, separate pill tabs, and
 * cards whose details sit in a two-column "Label: value" grid.
 *
 * Specty decodes a whole file to read its spectrum. A stream has no such measurement here, so the verdict is drawn
 * only from what Natune knows: the codec, and the format the decoder actually received during playback.
 */

package app.natune.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.natune.R
import app.natune.db.entities.FormatEntity
import app.natune.ui.player.StreamQualityTier
import app.natune.ui.player.streamQuality
import java.util.Locale

/** One detail in a sheet card. [wide] details (long notes) take the whole row instead of half. */
internal data class MediaInfoDetail(
    val iconRes: Int,
    val label: String,
    val value: String,
    val multiline: Boolean = false,
) {
    val wide: Boolean get() = multiline || value.length > 28
}

/** A format chip; the codec chip is the accented one, as in Specty. */
@Composable
internal fun MediaInfoChip(text: String, accent: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Separate pill tabs: the selected one filled, the others tonal, all the same width. */
@Composable
internal fun MediaInfoPillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Surface(
                shape = if (isSelected) CircleShape else RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f).height(52.dp).clickable { onSelect(index) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A card of details in two columns; tapping a detail copies its value. */
@Composable
internal fun MediaInfoGridCard(
    rows: List<MediaInfoDetail>,
    title: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onCopy: ((String) -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    Surface(shape = RoundedCornerShape(28.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(20.dp)) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            gridLines(rows).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    line.forEach { detail ->
                        MediaInfoGridCell(detail, onCopy, Modifier.weight(1f))
                    }
                    if (line.size == 1 && !line.first().wide) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Pairs short details side by side; a wide detail always gets a line of its own. */
private fun gridLines(rows: List<MediaInfoDetail>): List<List<MediaInfoDetail>> {
    val lines = mutableListOf<List<MediaInfoDetail>>()
    var pending: MediaInfoDetail? = null
    rows.forEach { detail ->
        val waiting = pending
        when {
            detail.wide -> {
                if (waiting != null) lines += listOf(waiting)
                pending = null
                lines += listOf(detail)
            }
            waiting == null -> pending = detail
            else -> {
                lines += listOf(waiting, detail)
                pending = null
            }
        }
    }
    pending?.let { lines += listOf(it) }
    return lines
}

@Composable
private fun MediaInfoGridCell(detail: MediaInfoDetail, onCopy: ((String) -> Unit)?, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(if (onCopy != null) Modifier.clickable { onCopy(detail.value) } else Modifier),
    ) {
        Icon(painter = painterResource(detail.iconRes), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = muted)) { append(detail.label.trimEnd(':') + ": ") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(detail.value) }
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (detail.multiline) 6 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The verdict card and the three metric tiles that open the sheet. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaInfoVerdict(format: FormatEntity?) {
    if (format == null) return
    val quality = remember(format) { format.streamQuality() }
    val rate = (format.evidenceSampleRate ?: format.sampleRate)?.takeIf { it > 0 }?.let(::kiloHertz)
    val depth = format.evidenceBitDepth?.takeIf { it > 0 }
    val kbps = format.bitrate.takeIf { it > 0 }?.let { it / 1000 }
    val (title, body) = when (quality.tier) {
        StreamQualityTier.HI_RES_LOSSLESS ->
            stringResource(R.string.media_verdict_hires_title) to
                stringResource(R.string.media_verdict_hires_body, quality.codec, depth?.toString() ?: "?", rate ?: "?")
        StreamQualityTier.LOSSLESS ->
            stringResource(R.string.media_verdict_lossless_title) to
                stringResource(R.string.media_verdict_lossless_body, quality.codec, depth?.toString() ?: "16", rate ?: "44.1")
        StreamQualityTier.LOSSY ->
            stringResource(R.string.media_verdict_lossy_title, quality.codec) to
                if (kbps != null) stringResource(R.string.media_verdict_lossy_body, quality.codec, kbps)
                else stringResource(R.string.media_verdict_lossy_body_unknown_rate, quality.codec)
        StreamQualityTier.UNVERIFIED ->
            stringResource(R.string.media_verdict_unverified_title) to
                stringResource(R.string.media_verdict_unverified_body, quality.codec)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Surface(
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(if (quality.isLossless) R.drawable.flamingo_quality_lossless else R.drawable.info),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(text = body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.bitrate), value = kbps?.toString() ?: "—", unit = "kbps",
                color = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.loudness),
                value = format.loudnessDb?.let { String.format(Locale.US, "%.1f", it) } ?: "—", unit = "dB",
                color = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.sample_rate), value = rate ?: "—", unit = "kHz",
                color = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, unit: String, color: Color, content: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = color, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = label, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.72f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(text = value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
            Text(text = unit, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.72f))
        }
    }
}

private fun kiloHertz(hz: Int): String =
    if (hz % 1000 == 0) (hz / 1000).toString() else String.format(Locale.US, "%.1f", hz / 1000.0)
