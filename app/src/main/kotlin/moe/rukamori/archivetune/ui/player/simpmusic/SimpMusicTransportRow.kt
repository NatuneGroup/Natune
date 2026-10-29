package moe.rukamori.archivetune.ui.player.simpmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.player.PlayerControlKind
import moe.rukamori.archivetune.ui.player.playerControlMotion
import moe.rukamori.archivetune.ui.player.rememberPlayerControlMotion

/** SimpMusic's accent, the tint its shuffle and repeat take when active. */
private val Seed = Color(0xFF8ECAE6)

/**
 * Shuffle, previous, play/pause, next, repeat — SimpMusic's PlayerControlLayout.
 *
 * The sizes are theirs, not approximations: a 96dp row, each control centred in its own weighted
 * cell, 32dp for shuffle and repeat, 42dp for the skips, and 72dp for the play button — which is a
 * FILLED DISC glyph (PlayCircle), not a bare triangle with a circle drawn round it. The active
 * tint is SimpMusic's seed blue.
 */
@Composable
internal fun SimpMusicTransportRow(
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val previousMotion = rememberPlayerControlMotion(PlayerControlKind.Previous)
    val nextMotion = rememberPlayerControlMotion(PlayerControlKind.Next)

    Row(
        modifier = modifier.height(96.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpMusicControl(
            cell = 42.dp,
            icon = 32.dp,
            painter = painterResource(R.drawable.simpmusic_shuffle),
            contentDescription = stringResource(R.string.shuffle),
            tint = if (shuffleEnabled) Seed else Color.White,
            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
            iconModifier = Modifier.playerControlMotion(PlayerControlKind.Shuffle, shuffleEnabled),
        )
        SimpMusicControl(
            cell = 52.dp,
            icon = 42.dp,
            painter = painterResource(R.drawable.simpmusic_skip_previous),
            contentDescription = stringResource(R.string.widget_previous),
            tint = Color.White.copy(alpha = if (canSkipPrevious) 1f else 0.4f),
            enabled = canSkipPrevious,
            onClick = previousMotion.onClick { playerConnection.seekToPrevious() },
            iconModifier = previousMotion.modifier,
        )
        SimpMusicControl(
            cell = 96.dp,
            icon = 72.dp,
            painter =
                painterResource(
                    if (isPlaying) R.drawable.simpmusic_pause_circle else R.drawable.simpmusic_play_circle,
                ),
            contentDescription = stringResource(if (isPlaying) R.string.widget_pause else R.string.play),
            tint = Color.White,
            onClick = { playerConnection.player.togglePlayPause() },
            iconModifier = Modifier.playerControlMotion(PlayerControlKind.PlayPause, isPlaying),
        )
        SimpMusicControl(
            cell = 52.dp,
            icon = 42.dp,
            painter = painterResource(R.drawable.simpmusic_skip_next),
            contentDescription = stringResource(R.string.next),
            tint = Color.White.copy(alpha = if (canSkipNext) 1f else 0.4f),
            enabled = canSkipNext,
            onClick = nextMotion.onClick { playerConnection.seekToNext() },
            iconModifier = nextMotion.modifier,
        )
        SimpMusicControl(
            cell = 42.dp,
            icon = 32.dp,
            painter =
                painterResource(
                    if (repeatMode == Player.REPEAT_MODE_ONE) {
                        R.drawable.simpmusic_repeat_one
                    } else {
                        R.drawable.simpmusic_repeat
                    },
                ),
            contentDescription =
                stringResource(
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                        Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                        else -> R.string.repeat_mode_off
                    },
                ),
            tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White else Seed,
            iconModifier = Modifier.playerControlMotion(PlayerControlKind.Repeat, repeatMode),
            onClick = {
                playerConnection.player.repeatMode =
                    when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
            },
        )
    }
}

/**
 * One transport control: a circular ripple [cell] wide holding an [icon]-wide glyph, centred in its
 * own weighted slot. A plain IconButton cannot express this — it forces a 48dp touch target and its
 * own icon size, which is what made the row's spacing wrong.
 */
@Composable
private fun RowScope.SimpMusicControl(
    cell: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.unit.Dp,
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(cell)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(icon).then(iconModifier),
            )
        }
    }
}
