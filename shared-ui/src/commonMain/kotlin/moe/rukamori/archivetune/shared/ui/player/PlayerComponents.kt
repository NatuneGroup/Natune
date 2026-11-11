package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import moe.rukamori.archivetune.shared.ui.menu.LocalOverflowMenuAnchor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class PlayerSymbol { PREVIOUS, NEXT, PLAY, PAUSE, REPLAY, SHUFFLE, REPEAT, HEART, SHARE, MORE, SLEEP, DOWN, LYRICS, QUEUE, BACK, EXPAND, OUTPUT, STAR, VOLUME_LOW, VOLUME_UP, ADD, BEDTIME, CLOSE_X }

internal fun PlayerAction.symbol() = when (this) {
    PlayerAction.PREVIOUS -> PlayerSymbol.PREVIOUS
    PlayerAction.NEXT -> PlayerSymbol.NEXT
    PlayerAction.SHUFFLE -> PlayerSymbol.SHUFFLE
    PlayerAction.REPEAT -> PlayerSymbol.REPEAT
    PlayerAction.LIKE -> PlayerSymbol.HEART
    PlayerAction.SHARE -> PlayerSymbol.SHARE
    PlayerAction.SLEEP -> PlayerSymbol.SLEEP
    PlayerAction.COLLAPSE -> PlayerSymbol.DOWN
    PlayerAction.OUTPUT -> PlayerSymbol.OUTPUT
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
    PlayerAction.OUTPUT -> Res.string.m5_output
    else -> Res.string.m5_more
}

/** Only a phone's artwork page collapses; with a panel open or fullscreen lyrics the same control steps back. */
@Composable
internal fun collapseSymbolAndLabel(c: PlayerRenderContext): Pair<PlayerSymbol, StringResource> =
    if (LocalAndroidPhonePresentation.current && c.interaction.panel == PlayerPanel.ARTWORK && !c.interaction.fullscreenLyrics) {
        PlayerSymbol.DOWN to Res.string.m5_collapse
    } else {
        PlayerSymbol.BACK to Res.string.m5_back
    }

internal const val STYLE_COLLAPSE_TAG = "player-style-collapse"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    size: Dp = if (large) 76.dp else 48.dp,
    iconSize: Dp = if (large) 34.dp else 24.dp,
    width: Dp? = null,
    container: Color? = null,
    contentColor: Color = Color.Unspecified,
    shape: Shape = if (square) RoundedCornerShape(20.dp) else CircleShape,
) {
    val menuAnchor = LocalOverflowMenuAnchor.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val click = { if (symbol == PlayerSymbol.MORE) menuAnchor?.bounds = bounds; onClick() }
    val containerColor = container ?: if (large || secondary) MaterialTheme.colorScheme.primaryContainer else null
    val color = when {
        contentColor != Color.Unspecified -> contentColor
        containerColor != null -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> LocalContentColor.current
    }
    val kind = symbol.controlKind()
    val environment = LocalPlayerControlMotion.current
    val motion = kind?.let { controlKind ->
        rememberPlayerControlMotion(controlKind, when (controlKind) {
            PlayerControlKind.PlayPause -> symbol
            PlayerControlKind.Repeat -> if (selected == false) PlayerRepeat.OFF else environment.repeat
            PlayerControlKind.Shuffle, PlayerControlKind.Like, PlayerControlKind.Queue, PlayerControlKind.Lyrics -> selected
            PlayerControlKind.Next, PlayerControlKind.Previous -> null
        }, environment.active)
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale = animateFloatAsState(if (pressed && enabled && environment.active) PlayerMotionSpecs.PressScale else 1f,
        if (environment.active) PlayerMotionSpecs.response() else snap(), label = "playerControlPress")
    val animatedShape = rememberPlayerButtonShape(symbol == PlayerSymbol.PAUSE, pressed, shape, environment.active)
    val fill by animateColorAsState(containerColor ?: Color.Transparent,
        if (environment.fades) tween(PlayerMotionSpecs.FadeMillis) else snap(), label = "playerControlFill")
    val tint by animateColorAsState(color,
        if (environment.fades) tween(PlayerMotionSpecs.FadeMillis) else snap(), label = "playerControlColor")
    val onButtonClick = if (motion == null) click else { { motion.tap(); click() } }
    val buttonModifier = modifier
        .then(if (symbol == PlayerSymbol.MORE) Modifier.onGloballyPositioned { bounds = it.boundsInWindow() } else Modifier)
        .size(width ?: size, size)
        .graphicsLayer { scaleX = pressScale.value; scaleY = pressScale.value }
        .then(if (containerColor != null) Modifier.background(fill, animatedShape) else Modifier)
        .semantics {
            contentDescription = label
            if (selected != null) this.selected = selected
            if (stateLabel != null) stateDescription = stateLabel
        }
    IconButton(onClick = onButtonClick, enabled = enabled, interactionSource = source, modifier = buttonModifier) {
        val glyphModifier = Modifier.size(iconSize).then(motion?.modifier ?: Modifier)
        if (kind == PlayerControlKind.PlayPause && !environment.active) {
            PlayerControlCrossfade(symbol, environment.fades, glyphModifier) { icon ->
                PlayerGlyph(icon, tint, enabled, selected, Modifier.size(iconSize))
            }
        } else if (kind == PlayerControlKind.PlayPause) {
            PlayerControlCrossfade(symbol == PlayerSymbol.REPLAY, environment.fades, glyphModifier) { replay ->
                if (replay) PlayerGlyph(PlayerSymbol.REPLAY, tint, enabled, selected, Modifier.size(iconSize))
                else PlayerPlayPauseGlyph(symbol == PlayerSymbol.PAUSE,
                    tint.copy(alpha = tint.alpha * if (enabled) 1f else .38f), Modifier.size(iconSize), environment.active)
            }
        } else {
            PlayerControlCrossfade(Triple(symbol, selected, if (symbol == PlayerSymbol.REPEAT) environment.repeat else PlayerRepeat.OFF), environment.fades, glyphModifier) { (icon, chosen, repeat) ->
                PlayerGlyph(icon, tint, enabled, chosen, Modifier.size(iconSize), repeat)
            }
        }
    }
}

private fun PlayerSymbol.controlKind(): PlayerControlKind? = when (this) {
    PlayerSymbol.PLAY, PlayerSymbol.PAUSE, PlayerSymbol.REPLAY -> PlayerControlKind.PlayPause
    PlayerSymbol.NEXT -> PlayerControlKind.Next
    PlayerSymbol.PREVIOUS -> PlayerControlKind.Previous
    PlayerSymbol.SHUFFLE -> PlayerControlKind.Shuffle
    PlayerSymbol.REPEAT -> PlayerControlKind.Repeat
    PlayerSymbol.HEART -> PlayerControlKind.Like
    PlayerSymbol.QUEUE -> PlayerControlKind.Queue
    PlayerSymbol.LYRICS -> PlayerControlKind.Lyrics
    else -> null
}

@Composable
internal fun PlayerGlyph(symbol: PlayerSymbol, color: Color, enabled: Boolean, selected: Boolean?, modifier: Modifier, repeat: PlayerRepeat = LocalPlayerControlMotion.current.repeat) {
    val drawable = transportDrawable(symbol, LocalPlayerGlyphStyle.current) ?: when (symbol) {
        PlayerSymbol.PREVIOUS -> Res.drawable.solar_skip_previous_linear
        PlayerSymbol.NEXT -> Res.drawable.solar_skip_next_linear
        PlayerSymbol.PLAY -> Res.drawable.solar_play_linear
        PlayerSymbol.PAUSE -> Res.drawable.solar_pause_linear
        PlayerSymbol.REPLAY -> Res.drawable.solar_replay_linear
        PlayerSymbol.SHUFFLE -> if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current &&
            LocalPlayerGlyphStyle.current == moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle.V4)
            Res.drawable.reference_cinematic_shuffle else Res.drawable.solar_shuffle
        PlayerSymbol.REPEAT -> if (repeat == PlayerRepeat.ONE)
            if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current &&
                LocalPlayerGlyphStyle.current == moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle.V4)
                Res.drawable.reference_cinematic_repeat_one else Res.drawable.solar_repeat_one
            else if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current &&
                LocalPlayerGlyphStyle.current == moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle.V4)
                Res.drawable.reference_cinematic_repeat else Res.drawable.solar_repeat
        PlayerSymbol.HEART -> if (selected == true) Res.drawable.solar_heart_bold else Res.drawable.solar_heart_linear
        PlayerSymbol.SHARE -> if (LocalPlayerGlyphStyle.current == moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle.TIKTOK)
            Res.drawable.solar_share_linear else Res.drawable.reference_player_share
        PlayerSymbol.MORE -> Res.drawable.solar_more
        PlayerSymbol.SLEEP, PlayerSymbol.BEDTIME -> Res.drawable.solar_sleep
        PlayerSymbol.DOWN -> Res.drawable.solar_down
        PlayerSymbol.LYRICS -> Res.drawable.solar_lyrics
        PlayerSymbol.QUEUE -> Res.drawable.reference_player_queue_music
        PlayerSymbol.BACK -> Res.drawable.solar_arrow_left_linear
        PlayerSymbol.EXPAND -> Res.drawable.solar_fullscreen_linear
        PlayerSymbol.OUTPUT -> Res.drawable.solar_cast
        PlayerSymbol.STAR -> Res.drawable.solar_star
        PlayerSymbol.VOLUME_LOW -> Res.drawable.solar_volume
        PlayerSymbol.VOLUME_UP -> Res.drawable.solar_volume_up_linear
        PlayerSymbol.ADD -> Res.drawable.solar_add_circle_linear
        PlayerSymbol.CLOSE_X -> Res.drawable.solar_close
    }
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier,
        tint = color.copy(alpha = color.alpha * if (enabled) 1f else .38f))
}

@Composable
internal fun ActionButton(action: PlayerAction, c: PlayerRenderContext, modifier: Modifier = Modifier, secondary: Boolean = false) {
    // An action with no owner on this host is hidden rather than offered as a control that only reports itself.
    val supported = action in c.state.actionSupport
    if (!supported && !moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current) return
    val outputButton = c.slots.outputButton
    if (action == PlayerAction.OUTPUT && outputButton != null) {
        outputButton(modifier.size(48.dp), LocalContentColor.current, supported)
        return
    }
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
    val (symbol, labelRes) = if (action == PlayerAction.COLLAPSE) collapseSymbolAndLabel(c) else action.symbol() to action.label()
    PlayerIcon(symbol, stringResource(labelRes) + if (supported) "" else " • " + stringResource(Res.string.phone_not_available_on_ios), { c.actions.onAction(action) },
        if (action == PlayerAction.COLLAPSE) modifier.testTag(STYLE_COLLAPSE_TAG) else modifier,
        enabled = supported && when(action) {
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
        PlayerBufferingFade(loading, c.fadeActive) {
            val modifier = Modifier.size(if(large) 72.dp else 44.dp).testTag("player-loading")
            if(c.motionActive) CircularProgressIndicator(modifier, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { .7f }, modifier = modifier, strokeWidth = 2.dp)
        }
    }
}

/** Title and artist, tappable only where the host actually answers the title and artist actions. */
@Composable
internal fun PlayerMetadata(c: PlayerRenderContext, centered: Boolean = false, editorial: Boolean = false) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if(centered) Alignment.CenterHorizontally else Alignment.Start) {
        val title: @Composable () -> Unit = {
            PlayerTextSwap(c.state.title.ifBlank { stringResource(Res.string.m5_no_track) }, c.fadeActive,
                alignment = if(centered) Alignment.TopCenter else Alignment.TopStart) { text ->
                Text(text, maxLines = if(editorial) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if(editorial) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                    fontFamily = c.state.spatialFont.takeIf { c.preferences.style.name == "SPATIALFLOW" })
            }
        }
        if (PlayerAction.TITLE in c.state.actionSupport) {
            TextButton(onClick = { c.actions.onAction(PlayerAction.TITLE) }) { title() }
        } else {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { title() }
        }
        val artist: @Composable () -> Unit = {
            PlayerTextSwap(c.state.artist, c.fadeActive, alignment = if(centered) Alignment.TopCenter else Alignment.TopStart) {
                MarqueeText(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (PlayerAction.ARTIST in c.state.actionSupport) {
            TextButton(onClick = { c.actions.onAction(PlayerAction.ARTIST) }) { artist() }
        } else {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { artist() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeek(
    c: PlayerRenderContext,
    native: PlayerSliderStyle = PlayerSliderStyle.Simple,
    remaining: Boolean = false,
    codec: Boolean = false,
) {
    val duration = c.state.playback?.durationMs?.takeIf { it > 0L }
    val progress = rememberPlayerProgress(c)
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
        val seekEnabled = duration != null && c.state.canSeek && c.state.playback?.isSeekable == true
        val colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color,
            activeTickColor = color, inactiveTrackColor = color.copy(alpha = when (style) {
                PlayerSliderStyle.Thick -> .28f
                PlayerSliderStyle.Simple -> .15f
                else -> .22f
            }))
        PlayerSeekSlider(progress, duration,
            onPreview = { c.interaction.preview(it,c.state) },
            source = interactionSource, motion = c.motionActive,
            onCommit = { c.interaction.commit(c.state,c.actions.seekTo) },
            enabled = seekEnabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            thumb = { scaled ->
                if (style != PlayerSliderStyle.Thick && style != PlayerSliderStyle.Simple)
                    SliderDefaults.Thumb(interactionSource, modifier = scaled, colors = colors, enabled = seekEnabled)
            },
            track = { sliderState ->
                if (style == PlayerSliderStyle.Standard) {
                    SliderDefaults.Track(sliderState, colors = colors, enabled = seekEnabled)
                } else {
                    val trackHeight = when (style) {
                        PlayerSliderStyle.Thick -> 12.dp
                        PlayerSliderStyle.Simple -> 3.dp
                        else -> 6.dp
                    }
                    Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                        val fraction = progress.fraction(duration)
                        val cy = size.height / 2
                        val h = trackHeight.toPx()
                        drawLine(colors.inactiveTrackColor, Offset(0f, cy), Offset(size.width, cy), h, StrokeCap.Round)
                        if (style == PlayerSliderStyle.Wavy || style == PlayerSliderStyle.Circular) {
                            val path = Path()
                            val amplitude = if (c.motionActive && c.state.playback?.playWhenReady == true) 2.dp.toPx() else 0f
                            for (i in 0..80) {
                                val x = size.width * fraction * i / 80f
                                val y = cy + sin(x / 12.dp.toPx() * PI.toFloat()) * amplitude
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color, style = Stroke(h, cap = StrokeCap.Round))
                        } else drawLine(color, Offset(0f, cy), Offset(size.width * fraction, cy), h, StrokeCap.Round)
                    }
                }
            })
        PlayerSeekLabels(progress, duration, remaining,
            c.state.formatLabel?.takeIf { codec && c.preferences.showCodec && it.isNotBlank() }, c.state.formatLossless)
    }
}

@Composable
private fun PlayerSeekLabels(progress: PlayerProgress, duration: Long?, remaining: Boolean, quality: String?, lossless: Boolean) {
    val seconds by remember(progress) { derivedStateOf { progress.positionMs / 1000L } }
    val style = MaterialTheme.typography.labelSmall.tabularFigures()
    val start = playerTime(seconds * 1000L)
    val end = if (duration == null) "—" else if (remaining) "−${playerTime((duration - seconds * 1000L).coerceAtLeast(0))}" else playerTime(duration)
    if (quality == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = style)
        Text(end, style = style)
    } else Box(Modifier.fillMaxWidth().testTag("player-seek-quality-row")) {
        // Centred rather than spaced between the timestamps, so the badge holds still as the digits change.
        Text(start, modifier = Modifier.align(Alignment.CenterStart), style = style)
        QualityBadge(quality, lossless, Modifier.align(Alignment.Center).padding(horizontal = 8.dp).testTag("player-seek-quality"))
        Text(end, modifier = Modifier.align(Alignment.CenterEnd), style = style)
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
        // A native route control is offered only by hosts that supply one; the rest keep their own output action.
        if(metrolist && c.slots.outputButton != null) ActionButton(PlayerAction.OUTPUT,c)
        ActionButton(PlayerAction.SLEEP,c)
        ActionButton(PlayerAction.MORE,c)
    }
}
