package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.player.*

/**
 * SimpMusic look over the palette-gradient backdrop: a 48dp centred lyric band sits between
 * the artwork and the title row (its height feeds the artwork sizing, as in the original),
 * then title+like, the scrubber and the five-button transport.
 */
@Composable
internal fun SimpMusicPlayer(c: PlayerRenderContext) {
    PlayerColumn(Modifier.testTag("simpmusic-content")) {
        // The shared close control is the single collapse, as in Android's top bar. The menu stays at the top right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton(PlayerAction.MORE, c)
        }
        if (c.landscape) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerArtworkPager(c, Modifier.weight(1f))
            Column(Modifier.weight(1f)) { SimpMusicControls(c) }
        } else {
            if (LocalDesktopLayout.current) {
                // The desktop column keeps its full-width sleeve and scrolls.
                PlayerArtworkPager(c, Modifier.fillMaxWidth())
            } else {
                // A phone sleeve is square and gives up height: weight(fill = false) caps it at the room the rows
                // below leave in the viewport, so the transport and action rows always stay on screen.
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    PlayerArtworkPager(c)
                }
            }
            SimpMusicLyricBand(c)
            SimpMusicControls(c)
        }
        BottomControls(c)
    }
}

@Composable
private fun SimpMusicLyricBand(c: PlayerRenderContext) {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        c.slots.compactLyrics(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SimpMusicControls(c: PlayerRenderContext) {
    Column {
        if (c.landscape) SimpMusicLyricBand(c)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PlayerMetadata(c) }; ActionButton(PlayerAction.LIKE, c)
        }
        PlayerSeek(c, codec = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ActionButton(PlayerAction.SHUFFLE, c); ActionButton(PlayerAction.PREVIOUS, c)
            PlayButton(c); ActionButton(PlayerAction.NEXT, c); ActionButton(PlayerAction.REPEAT, c)
        }
    }
}
