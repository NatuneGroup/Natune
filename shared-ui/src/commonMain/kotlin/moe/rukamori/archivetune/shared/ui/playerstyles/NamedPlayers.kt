package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun BitChordPlayer(c: PlayerRenderContext) {
    PlayerColumn(Modifier.testTag("bitchord-content")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE,c); ActionButton(PlayerAction.MORE,c)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PlayerSleeve(c,Modifier.weight(1f))
            Column(Modifier.then(if(c.landscape) Modifier.weight(1f) else Modifier)) {
                if(c.landscape) {
                    PlayerMetadata(c,centered=true)
                    c.slots.compactLyrics(Modifier.fillMaxWidth().height(76.dp))
                    PlayerSeek(c)
                    NativeTransport(c,square=true)
                }
                Row { ActionButton(PlayerAction.LIKE,c); ActionButton(PlayerAction.SHARE,c) }
            }
        }
        if(!c.landscape) {
            PlayerMetadata(c,centered=true)
            c.slots.compactLyrics(Modifier.fillMaxWidth().height(76.dp))
            PlayerSeek(c)
            NativeTransport(c,square=true)
        }
        PlayerVolume(c)
        BottomControls(c)
        c.slots.queue(Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
internal fun TikTokPlayer(c: PlayerRenderContext) {
    Box(Modifier.fillMaxSize().testTag("tiktok-content")) {
        PlayerArtworkPager(c,Modifier.fillMaxSize(),vertical=true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Transparent,Color.Black.copy(alpha=.9f)))))
        Column(Modifier.align(Alignment.CenterEnd)) {
            ActionButton(PlayerAction.LIKE,c)
            ActionButton(PlayerAction.SHARE,c)
            PlayerIcon(PlayerSymbol.QUEUE,stringResource(Res.string.m5_queue),{ c.interaction.panel=PlayerPanel.QUEUE })
            PlayerIcon(PlayerSymbol.LYRICS,stringResource(Res.string.m5_lyrics),{ c.interaction.panel=PlayerPanel.LYRICS })
            ActionButton(PlayerAction.MORE,c)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end=56.dp)) {
            PlayerMetadata(c)
            c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
            PlayerSeek(c)
            NativeTransport(c)
        }
        ActionButton(PlayerAction.COLLAPSE,c)
    }
}

@Composable
internal fun SimpMusicPlayer(c: PlayerRenderContext) {
    PlayerColumn(Modifier.testTag("simpmusic-content")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE,c); ActionButton(PlayerAction.MORE,c)
        }
        if(c.landscape) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlayerArtworkPager(c,Modifier.weight(1f))
            Column(Modifier.weight(1f)) { SimpMusicControls(c) }
        } else {
            PlayerArtworkPager(c,Modifier.fillMaxWidth())
            SimpMusicControls(c)
        }
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            c.slots.compactLyrics(Modifier.fillMaxWidth().height(100.dp).padding(16.dp))
        }
        BottomControls(c)
    }
}

@Composable
private fun SimpMusicControls(c: PlayerRenderContext) {
    Column {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PlayerMetadata(c) }; ActionButton(PlayerAction.LIKE,c)
        }
        PlayerSeek(c)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.PREVIOUS,c)
            PlayButton(c); ActionButton(PlayerAction.NEXT,c); ActionButton(PlayerAction.REPEAT,c)
        }
    }
}

@Composable
internal fun SpatialFlowPlayer(c: PlayerRenderContext) {
    PlayerColumn(Modifier.testTag(if(c.state.spatialFont==null) "spatial-font-fallback" else "spatial-font-installed")) {
        ActionButton(PlayerAction.COLLAPSE,c)
        if(c.landscape) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlayerArtworkPager(c,Modifier.weight(1f))
            Column(Modifier.weight(1f)) { SpatialFlowControls(c) }
        } else {
            PlayerArtworkPager(c,Modifier.fillMaxWidth(.86f).align(Alignment.CenterHorizontally))
            SpatialFlowControls(c)
        }
        c.slots.compactLyrics(Modifier.fillMaxWidth().height(88.dp))
        BottomControls(c)
    }
}

@Composable
private fun SpatialFlowControls(c: PlayerRenderContext) {
    Column {
        PlayerMetadata(c,centered=true)
        PlayerSeek(c,native=PlayerSliderStyle.Wavy)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
            ActionButton(PlayerAction.PREVIOUS,c); PlayButton(c,large=false)
            ActionButton(PlayerAction.NEXT,c); ActionButton(PlayerAction.LIKE,c)
        }
    }
}

@Composable
internal fun LooperPlayer(c: PlayerRenderContext) {
    val direction=LocalLayoutDirection.current
    val actions by rememberUpdatedState(c.actions)
    val canSeek by rememberUpdatedState(c.state.canSeek && c.state.playback?.isSeekable==true)
    PlayerColumn(Modifier.testTag("looper-content")) {
        PlayerMetadata(c,editorial=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.REPEAT,c)
        }
        PlayerArtworkPager(c,Modifier.fillMaxWidth().pointerInput(direction) {
            detectTapGestures(onDoubleTap={ offset ->
                if(!canSeek) return@detectTapGestures
                val forward=if(direction==LayoutDirection.Ltr) offset.x>=size.width/2 else offset.x<size.width/2
                actions.onAction(if(forward) PlayerAction.SEEK_FORWARD else PlayerAction.SEEK_BACKWARD)
            })
        })
        PlayerSeek(c,native=PlayerSliderStyle.Thick)
        NativeTransport(c,square=true)
        c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
        BottomControls(c)
    }
}

@Composable
internal fun FlamingoPlayer(c: PlayerRenderContext) {
    val pages=rememberPagerState(initialPage=if(c.landscape) 1 else c.interaction.panel.ordinal,pageCount={ 3 })
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if(c.landscape) c.interaction.panel=PlayerPanel.LYRICS }
    LaunchedEffect(c.interaction.panel) {
        if(pages.currentPage!=c.interaction.panel.ordinal) {
            programmatic=true
            try { pages.scrollToPage(c.interaction.panel.ordinal) } finally { programmatic=false }
        }
    }
    LaunchedEffect(pages) {
        snapshotFlow { pages.settledPage }.collect { page ->
            if(!programmatic) c.interaction.panel=PlayerPanel.entries[page]
        }
    }
    Column(Modifier.fillMaxSize().testTag("flamingo-content")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            for(panel in PlayerPanel.entries) TextButton(onClick={ c.interaction.panel=panel }) {
                Text(stringResource(when(panel) {
                    PlayerPanel.ARTWORK -> Res.string.m5_album
                    PlayerPanel.LYRICS -> Res.string.m5_lyrics
                    PlayerPanel.QUEUE -> Res.string.m5_queue
                }))
            }
        }
        HorizontalPager(pages,Modifier.weight(1f).fillMaxWidth().testTag("flamingo-content-pages")) { page ->
            when(PlayerPanel.entries[page]) {
                PlayerPanel.ARTWORK -> PlayerColumn {
                    PlayerSleeve(c,Modifier.fillMaxWidth())
                    PlayerMetadata(c)
                }
                PlayerPanel.LYRICS -> Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        PlayerSleeve(c,Modifier.width(64.dp),canvasAllowed=false)
                        PlayerIcon(PlayerSymbol.EXPAND,stringResource(Res.string.m5_fullscreen),{ c.interaction.fullscreenLyrics=true })
                    }
                    c.slots.lyrics(Modifier.weight(1f).fillMaxWidth())
                }
                PlayerPanel.QUEUE -> Column(Modifier.fillMaxSize()) {
                    PlayerSleeve(c,Modifier.width(48.dp),canvasAllowed=false)
                    c.slots.queue(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        // All three pages retain transport; landscape starts on lyrics, not the album page.
        PlayerSeek(c)
        NativeTransport(c)
        PlayerVolume(c)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.REPEAT,c)
            ActionButton(PlayerAction.SLEEP,c); ActionButton(PlayerAction.MORE,c)
        }
    }
}
