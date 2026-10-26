package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin

internal enum class PlayerSymbol { PREVIOUS, NEXT, PLAY, PAUSE, REPLAY, SHUFFLE, REPEAT, HEART, SHARE, MORE, SLEEP, DOWN, LYRICS, QUEUE, BACK, EXPAND }

internal fun PlayerAction.symbol() = when (this) {
    PlayerAction.PREVIOUS -> PlayerSymbol.PREVIOUS
    PlayerAction.NEXT -> PlayerSymbol.NEXT
    PlayerAction.SHUFFLE -> PlayerSymbol.SHUFFLE
    PlayerAction.REPEAT -> PlayerSymbol.REPEAT
    PlayerAction.LIKE -> PlayerSymbol.HEART
    PlayerAction.SHARE -> PlayerSymbol.SHARE
    PlayerAction.SLEEP -> PlayerSymbol.SLEEP
    PlayerAction.COLLAPSE -> PlayerSymbol.DOWN
    else -> PlayerSymbol.MORE
}

internal fun PlayerAction.label(): StringResource = when (this) {
    PlayerAction.PREVIOUS -> Res.string.m5_previous
    PlayerAction.NEXT -> Res.string.m5_next
    PlayerAction.SHUFFLE -> Res.string.m5_shuffle
    PlayerAction.REPEAT -> Res.string.m5_repeat
    PlayerAction.LIKE -> Res.string.m5_like
    PlayerAction.SHARE -> Res.string.m5_share
    PlayerAction.SLEEP -> Res.string.m5_sleep
    PlayerAction.COLLAPSE -> Res.string.m5_collapse
    else -> Res.string.m5_more
}

@Composable
internal fun PlayerIcon(
    symbol: PlayerSymbol,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    large: Boolean = false,
    square: Boolean = false,
    secondary: Boolean = false,
    stateLabel: String? = null,
) {
    val size = if (large) 76.dp else 48.dp
    val color = if (large || secondary) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current
    val shape = if (square) RoundedCornerShape(20.dp) else CircleShape
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.size(size).then(
            if (large || secondary) Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape) else Modifier,
        ).semantics {
            contentDescription = label
            if (selected != null) this.selected = selected
            if (stateLabel != null) stateDescription = stateLabel
        },
    ) {
        Canvas(Modifier.size(if (large) 34.dp else 24.dp)) {
            val c = color.copy(alpha = if (enabled) 1f else .38f)
            val w = this.size.width
            val h = this.size.height
            val stroke = Stroke(w * .085f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) =
                drawLine(c, Offset(w * x, h * y), Offset(w * x2, h * y2), w * .085f, StrokeCap.Round)
            when (symbol) {
                PlayerSymbol.PLAY, PlayerSymbol.NEXT, PlayerSymbol.PREVIOUS -> {
                    val reverse = symbol == PlayerSymbol.PREVIOUS
                    val path = Path().apply {
                        if (reverse) { moveTo(w * .7f, h * .2f); lineTo(w * .25f, h * .5f); lineTo(w * .7f, h * .8f) }
                        else { moveTo(w * .3f, h * .2f); lineTo(w * .8f, h * .5f); lineTo(w * .3f, h * .8f) }
                        close()
                    }
                    drawPath(path, c)
                    if (symbol == PlayerSymbol.NEXT) line(.87f, .2f, .87f, .8f)
                    if (reverse) line(.15f, .2f, .15f, .8f)
                }
                PlayerSymbol.PAUSE -> { line(.33f, .2f, .33f, .8f); line(.67f, .2f, .67f, .8f) }
                PlayerSymbol.MORE -> for (x in listOf(.2f, .5f, .8f)) drawCircle(c, w * .06f, Offset(w * x, h * .5f))
                PlayerSymbol.HEART -> {
                    val path = Path().apply {
                        moveTo(w * .5f, h * .85f)
                        cubicTo(-w * .2f, h * .4f, w * .1f, -h * .1f, w * .5f, h * .3f)
                        cubicTo(w * .9f, -h * .1f, w * 1.2f, h * .4f, w * .5f, h * .85f)
                    }
                    drawPath(path, c, style = if (selected == true) androidx.compose.ui.graphics.drawscope.Fill else stroke)
                }
                PlayerSymbol.DOWN -> { line(.2f, .35f, .5f, .65f); line(.5f, .65f, .8f, .35f) }
                PlayerSymbol.BACK -> { line(.65f, .2f, .35f, .5f); line(.35f, .5f, .65f, .8f) }
                PlayerSymbol.SHUFFLE -> { line(.15f, .2f, .85f, .8f); line(.15f, .8f, .85f, .2f); line(.65f, .2f, .85f, .2f) }
                PlayerSymbol.REPEAT, PlayerSymbol.REPLAY -> {
                    drawArc(c, 40f, 290f, false, Offset(w * .15f, h * .15f), Size(w * .7f, h * .7f), style = stroke)
                    line(.8f, .15f, .8f, .4f)
                }
                PlayerSymbol.SHARE -> { line(.2f, .5f, .8f, .2f); line(.2f, .5f, .8f, .8f)
                    for ((x,y) in listOf(.2f to .5f, .8f to .2f, .8f to .8f)) drawCircle(c, w * .1f, Offset(w*x,h*y)) }
                PlayerSymbol.SLEEP -> { drawCircle(c, w * .32f, center = Offset(w*.5f,h*.5f), style=stroke); line(.5f,.3f,.5f,.5f); line(.5f,.5f,.65f,.6f) }
                PlayerSymbol.EXPAND -> { line(.2f,.4f,.2f,.2f); line(.2f,.2f,.4f,.2f); line(.8f,.6f,.8f,.8f); line(.8f,.8f,.6f,.8f) }
                PlayerSymbol.LYRICS, PlayerSymbol.QUEUE -> for (y in listOf(.25f,.5f,.75f)) line(.2f,y,if(symbol==PlayerSymbol.LYRICS && y==.75f) .6f else .8f,y)
            }
        }
    }
}

@Composable
internal fun ActionButton(action: PlayerAction, c: PlayerRenderContext, modifier: Modifier = Modifier, secondary: Boolean = false) {
    val selected = when(action) {
        PlayerAction.SHUFFLE -> c.state.shuffle
        PlayerAction.REPEAT -> c.state.repeat != PlayerRepeat.OFF
        PlayerAction.LIKE -> c.state.liked
        else -> null
    }
    val stateLabel = when {
        action == PlayerAction.REPEAT && c.state.repeat == PlayerRepeat.ONE -> stringResource(Res.string.m5_repeat_one)
        selected != null -> stringResource(if(selected) Res.string.m5_on else Res.string.m5_off)
        else -> null
    }
    PlayerIcon(action.symbol(), stringResource(action.label()), { c.actions.onAction(action) }, modifier,
        enabled = when(action) {
            PlayerAction.PREVIOUS -> c.state.canPrevious
            PlayerAction.NEXT -> c.state.canNext
            else -> true
        }, selected = selected, secondary = secondary, stateLabel = stateLabel)
}

@Composable
internal fun PlayButton(c: PlayerRenderContext, large: Boolean = true, square: Boolean = false) {
    val playback = c.state.playback
    val loading = playback?.isBuffering == true
    val label = stringResource(when {
        loading -> Res.string.m5_loading
        playback?.isEnded == true -> Res.string.m5_replay
        playback?.playWhenReady == true -> Res.string.m5_pause
        else -> Res.string.m5_play
    })
    Box(contentAlignment = Alignment.Center) {
        PlayerIcon(when {
            playback?.isEnded == true -> PlayerSymbol.REPLAY
            playback?.playWhenReady == true -> PlayerSymbol.PAUSE
            else -> PlayerSymbol.PLAY
        }, label, c.actions.playPause, enabled = playback != null, large = large, square = square)
        // Pause stays reachable while buffering; intent and observed advancement are not conflated.
        if (loading) {
            val modifier = Modifier.size(if(large) 72.dp else 44.dp).testTag("player-loading")
            if(c.motionActive) CircularProgressIndicator(modifier, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { .7f }, modifier = modifier, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun PlayerMetadata(c: PlayerRenderContext, centered: Boolean = false, editorial: Boolean = false) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if(centered) Alignment.CenterHorizontally else Alignment.Start) {
        TextButton(onClick = { c.actions.onAction(PlayerAction.TITLE) }) {
            Text(c.state.title.ifBlank { stringResource(Res.string.m5_no_track) }, maxLines = if(editorial) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                style = if(editorial) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                fontFamily = c.state.spatialFont.takeIf { c.preferences.style.name == "SPATIALFLOW" })
        }
        TextButton(onClick = { c.actions.onAction(PlayerAction.ARTIST) }) {
            Text(c.state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeek(c: PlayerRenderContext, native: PlayerSliderStyle = PlayerSliderStyle.Simple, remaining: Boolean = false) {
    val duration = c.state.playback?.durationMs?.takeIf { it > 0L }
    val position = (c.interaction.previewMs ?: c.state.positionMs).coerceAtLeast(0)
    val fraction = if(duration != null) (position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f
    val style = if(c.preferences.style.capabilities.sharedControls) c.preferences.slider else native
    val label = stringResource(Res.string.m5_seek)
    val color = LocalContentColor.current
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { event ->
            if(event is DragInteraction.Cancel) c.interaction.cancelPreview()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("seek-${style.name}")) {
        Slider(value = fraction, onValueChange = { c.interaction.preview(it,c.state) },
            interactionSource = interactionSource,
            onValueChangeFinished = { c.interaction.commit(c.state,c.actions.seekTo) },
            enabled = duration != null && c.state.canSeek && c.state.playback?.isSeekable == true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            track = {
                Canvas(Modifier.fillMaxWidth().height(if(style==PlayerSliderStyle.Thick) 12.dp else 5.dp)) {
                    val h = size.height
                    drawLine(color.copy(alpha=.2f),Offset(0f,h/2),Offset(size.width,h/2),h,StrokeCap.Round)
                    val buffered = c.state.playback?.bufferedPositionMs
                    if(duration!=null && buffered!=null) drawLine(color.copy(alpha=.3f),Offset(0f,h/2),
                        Offset(size.width*(buffered.toDouble()/duration).toFloat().coerceIn(0f,1f),h/2),h,StrokeCap.Round)
                    if(style==PlayerSliderStyle.Wavy) {
                        val path=Path()
                        for(i in 0..80) {
                            val x=size.width*fraction*i/80f
                            val y=h/2 + sin(x/12f*PI.toFloat())*3.dp.toPx()
                            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                        }
                        drawPath(path,color,style=Stroke(2.dp.toPx()))
                    } else drawLine(color,Offset(0f,h/2),Offset(size.width*fraction,h/2),h,StrokeCap.Round)
                    if(style==PlayerSliderStyle.Circular) drawCircle(color,8.dp.toPx(),Offset(size.width*fraction,h/2),style=Stroke(2.dp.toPx()))
                }
            })
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(playerTime(position),style=MaterialTheme.typography.labelSmall)
            Text(if(duration==null) "—" else if(remaining) "−${playerTime((duration-position).coerceAtLeast(0))}" else playerTime(duration),
                style=MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun playerTime(ms: Long): String {
    val seconds=ms.coerceAtLeast(0)/1000
    return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
}

@Composable
internal fun PlayerVolume(c: PlayerRenderContext) {
    val volume=c.state.volume?.takeIf { it.isFinite() } ?: return
    if(!c.preferences.showVolume || !c.preferences.style.capabilities.volume) return
    val label=stringResource(Res.string.m5_volume)
    Slider(volume.coerceIn(0f,1f),c.actions.setVolume,
        modifier=Modifier.fillMaxWidth().testTag("player-volume").semantics { contentDescription=label })
}

@Composable
internal fun BottomControls(c: PlayerRenderContext, metrolist: Boolean = false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
        PlayerIcon(PlayerSymbol.LYRICS,stringResource(Res.string.m5_lyrics),{ c.interaction.panel=PlayerPanel.LYRICS },
            selected=c.interaction.panel==PlayerPanel.LYRICS)
        if(metrolist) ActionButton(PlayerAction.SHUFFLE,c)
        PlayerIcon(PlayerSymbol.QUEUE,stringResource(Res.string.m5_queue),{ c.interaction.panel=PlayerPanel.QUEUE },
            selected=c.interaction.panel==PlayerPanel.QUEUE)
        if(metrolist) ActionButton(PlayerAction.REPEAT,c)
        ActionButton(PlayerAction.SLEEP,c)
        ActionButton(PlayerAction.MORE,c)
    }
}
