package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.playerstyles.ArtistLine
import moe.rukamori.archivetune.shared.ui.playerstyles.TitleLine
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared counterpart of Android #88: measured text determines the artwork edge; lyrics chrome has no like control. */
@Composable
internal fun FullscreenLyricsChrome(c: PlayerRenderContext) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("fullscreen-lyrics")) {
        LyricsTrackHeader(c, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        c.slots.lyrics(Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.m5_more), { c.actions.onAction(PlayerAction.MORE) })
            PlayerIcon(PlayerSymbol.DOWN, stringResource(Res.string.m5_back), { c.interaction.fullscreenLyrics = false })
        }
    }
}

@Composable
private fun LyricsTrackHeader(c: PlayerRenderContext, modifier: Modifier) {
    Layout(modifier = modifier, content = {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) {
            c.slots.artwork(c.state.playback?.occurrenceId, Modifier.fillMaxSize())
        }
        Column(verticalArrangement = Arrangement.Center) {
            TitleLine(c, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, onClick = false)
            ArtistLine(c, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, alpha = .72f, onClick = false)
        }
    }) { measurables, constraints ->
        val gap = (if (c.landscape) 8.dp else 14.dp).roundToPx().coerceAtMost(constraints.maxWidth)
        val available = (constraints.maxWidth - gap).coerceAtLeast(0)
        val intrinsic = measurables[1].minIntrinsicHeight(available).coerceIn(0, available)
        val text = measurables[1].measure(Constraints(maxWidth = (available - intrinsic).coerceAtLeast(0), maxHeight = constraints.maxHeight))
        val edge = text.height.coerceIn(0, available)
        val artwork = measurables[0].measure(Constraints.fixed(edge, edge))
        val height = constraints.constrainHeight(maxOf(text.height, artwork.height))
        layout(constraints.maxWidth, height) {
            artwork.placeRelative(0, (height - artwork.height) / 2)
            text.placeRelative(edge + gap, (height - text.height) / 2)
        }
    }
}
