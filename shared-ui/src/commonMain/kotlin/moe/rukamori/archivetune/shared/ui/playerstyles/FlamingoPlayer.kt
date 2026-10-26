package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Flamingo look: three swiped pages (album / lyrics / queue) behind a tab row, with
 * transport, scrubber and the legacy volume row retained below the pager on every page —
 * matching the original's persistent PlayingBar. Landscape opens on the lyrics page.
 * The blur/motion backdrop is owned by PlayerBackground (flamingoMotion preference).
 */
@Composable
internal fun FlamingoPlayer(c: PlayerRenderContext) {
    val pages = rememberPagerState(initialPage = if (c.landscape) 1 else c.interaction.panel.ordinal, pageCount = { 3 })
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (c.landscape) c.interaction.panel = PlayerPanel.LYRICS }
    LaunchedEffect(c.interaction.panel) {
        if (pages.currentPage != c.interaction.panel.ordinal) {
            programmatic = true
            try { pages.scrollToPage(c.interaction.panel.ordinal) } finally { programmatic = false }
        }
    }
    LaunchedEffect(pages) {
        snapshotFlow { pages.settledPage }.collectLatest { page ->
            if (!programmatic) c.interaction.panel = PlayerPanel.entries[page]
        }
    }
    Column(Modifier.fillMaxSize().testTag("flamingo-content")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (panel in PlayerPanel.entries) TextButton(onClick = { c.interaction.panel = panel }) {
                Text(
                    stringResource(
                        when (panel) {
                            PlayerPanel.ARTWORK -> Res.string.m5_album
                            PlayerPanel.LYRICS -> Res.string.m5_lyrics
                            PlayerPanel.QUEUE -> Res.string.m5_queue
                        }
                    )
                )
            }
        }
        HorizontalPager(pages, Modifier.weight(1f).fillMaxWidth().testTag("flamingo-content-pages")) { page ->
            when (PlayerPanel.entries[page]) {
                PlayerPanel.ARTWORK -> PlayerColumn {
                    PlayerSleeve(c, Modifier.fillMaxWidth())
                    PlayerMetadata(c)
                }
                PlayerPanel.LYRICS -> Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerSleeve(c, Modifier.width(64.dp), canvasAllowed = false)
                        PlayerIcon(PlayerSymbol.EXPAND, stringResource(Res.string.m5_fullscreen),
                            { c.interaction.fullscreenLyrics = true })
                    }
                    c.slots.lyrics(Modifier.weight(1f).fillMaxWidth())
                }
                PlayerPanel.QUEUE -> Column(Modifier.fillMaxSize()) {
                    PlayerSleeve(c, Modifier.width(48.dp), canvasAllowed = false)
                    c.slots.queue(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        // All three pages retain transport, seek and volume — the original's PlayingBar stays pinned.
        PlayerSeek(c)
        NativeTransport(c)
        PlayerVolume(c)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionButton(PlayerAction.SHUFFLE, c); ActionButton(PlayerAction.REPEAT, c)
            ActionButton(PlayerAction.SLEEP, c); ActionButton(PlayerAction.MORE, c)
        }
    }
}
