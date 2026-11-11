package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.playerstyles.ArtistLine
import moe.rukamori.archivetune.shared.ui.playerstyles.TitleLine
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun FullscreenLyricsChrome(c: PlayerRenderContext) {
    val foreground = LocalContentColor.current
    Column(Modifier.fillMaxSize().testTag("fullscreen-lyrics")) {
        LyricsTrackHeader(c, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
        c.slots.lyrics(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val duration = c.state.playback?.durationMs?.takeIf { it > 0L }
            val position = c.interaction.previewMs ?: c.state.positionMs
            LyricsFlatSlider(if (duration == null) 0f else (position.toDouble() / duration).toFloat(),
                stringResource(Res.string.m5_seek), foreground,
                duration != null && c.state.canSeek && c.state.playback?.isSeekable == true,
                { c.interaction.preview(it, c.state) }, { c.interaction.commit(c.state, c.actions.seekTo) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(playerTime(position), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .54f))
                c.state.formatLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f),
                        modifier = Modifier.testTag("fullscreen-lyrics-quality"))
                }
                Text(if (duration == null) "" else "−${playerTime((duration - position).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .54f))
            }
            CompositionLocalProvider(LocalPlayerGlyphStyle provides PlayerDesignStyle.Default) {
                Row(Modifier.fillMaxWidth().testTag("fullscreen-lyrics-transport"),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    LyricsTransportGlyph(c, previous = true)
                    val playback = c.state.playback
                    Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
                        if (playback?.isBuffering == true) CircularProgressIndicator(Modifier.size(42.dp), color = foreground)
                        else PlayerIcon(if (playback?.playWhenReady == true) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
                            stringResource(if (playback?.playWhenReady == true) Res.string.m5_pause else Res.string.m5_play),
                            c.actions.playPause, enabled = playback != null, size = 74.dp, iconSize = 54.dp)
                    }
                    LyricsTransportGlyph(c, previous = false)
                }
            }
            val volume = c.state.volume?.takeIf { it.isFinite() }
            if (c.preferences.showVolume && volume != null) {
                Row(Modifier.fillMaxWidth().testTag("fullscreen-lyrics-volume"), verticalAlignment = Alignment.CenterVertically) {
                    PlayerGlyph(PlayerSymbol.VOLUME_LOW, foreground, true, null, Modifier.size(17.dp))
                    LyricsFlatSlider(volume, stringResource(Res.string.m5_volume), foreground, true,
                        c.actions.setVolume, {}, Modifier.weight(1f).padding(horizontal = 16.dp))
                    PlayerGlyph(PlayerSymbol.VOLUME_UP, foreground, true, null, Modifier.size(19.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).testTag("fullscreen-lyrics-footer"),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            val status = c.slots.lyricsStatus?.takeIf { it.occurrenceId == c.state.displayedOccurrenceId }
            Text(stringResource(if (status?.hasLyrics == false) Res.string.phone_lyrics_not_found else Res.string.m5_lyrics), style = MaterialTheme.typography.labelLarge,
                color = foreground.copy(alpha = .75f), modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(foreground.copy(alpha = .1f)).clickable { c.actions.onAction(PlayerAction.MORE) }
                    .padding(horizontal = 16.dp, vertical = 7.dp))
            PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.m5_more), { c.actions.onAction(PlayerAction.MORE) }, iconSize = 20.dp)
            PlayerIcon(PlayerSymbol.CLOSE_X, stringResource(Res.string.m5_back), { c.interaction.fullscreenLyrics = false }, iconSize = 20.dp)
        }
    }
}

@Composable
private fun LyricsTransportGlyph(c: PlayerRenderContext, previous: Boolean) {
    val action = if (previous) PlayerAction.PREVIOUS else PlayerAction.NEXT
    val motion = rememberPlayerControlMotion(if (previous) PlayerControlKind.Previous else PlayerControlKind.Next,
        null, c.motionActive)
    IconButton({ motion.tap(); c.actions.onAction(action) }, enabled = if (previous) c.state.canPrevious else c.state.canNext,
        modifier = Modifier.size(68.dp)) {
        Icon(painterResource(Res.drawable.reference_lyrics_fast_forward), stringResource(action.label()),
            Modifier.size(44.dp).graphicsLayer { scaleX = if (previous) -1f else 1f }.then(motion.modifier))
    }
}

@Composable
private fun LyricsFlatSlider(value: Float, label: String, color: Color, enabled: Boolean,
    onValue: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    Slider(value.coerceIn(0f, 1f), onValue, enabled = enabled, onValueChangeFinished = onFinished,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        thumb = {}, track = {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val opacity = if (enabled) 1f else .38f
                drawLine(color.copy(alpha = .28f * opacity), Offset(0f, center.y), Offset(size.width, center.y), size.height, StrokeCap.Round)
                drawLine(color.copy(alpha = .94f * opacity), Offset(0f, center.y), Offset(size.width * value.coerceIn(0f, 1f), center.y), size.height, StrokeCap.Round)
            }
        })
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
