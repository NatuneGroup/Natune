package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.player.ActionButton
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerArtworkPager
import moe.rukamori.archivetune.shared.ui.player.PlayerControlCrossfade
import moe.rukamori.archivetune.shared.ui.player.PlayerControlKind
import moe.rukamori.archivetune.shared.ui.player.PlayerBackgroundStyle
import moe.rukamori.archivetune.shared.ui.player.capabilities
import moe.rukamori.archivetune.shared.ui.player.PlayerGlyph
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerPanel
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerSeek
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.player.playerTime
import moe.rukamori.archivetune.shared.ui.player.rememberPlayerControlMotion
import moe.rukamori.archivetune.shared.ui.player.resolveBackground
import moe.rukamori.archivetune.shared.ui.player.sleeveHidden
import moe.rukamori.archivetune.shared.ui.player.sleeveRadiusOr
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_collapse
import moe.rukamori.archivetune.shared.ui.resources.m5_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m5_like
import moe.rukamori.archivetune.shared.ui.resources.m5_more
import moe.rukamori.archivetune.shared.ui.resources.m5_next
import moe.rukamori.archivetune.shared.ui.resources.m5_queue
import moe.rukamori.archivetune.shared.ui.resources.m5_seek
import moe.rukamori.archivetune.shared.ui.resources.m5_share
import org.jetbrains.compose.resources.stringResource

/** Rounded artwork square used by the numbered looks; canvas plays behind it where the style allows. */
@Composable
internal fun PlayerSleeve(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    radius: Float = 16f,
    canvasAllowed: Boolean = true,
) {
    val hidden=c.preferences.sleeveHidden
    val r=c.preferences.sleeveRadiusOr(radius)
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(r.dp)).graphicsLayer { alpha=if(hidden) 0f else 1f }
        .testTag("player-sleeve")) {
        if(canvasAllowed && c.state.canvasAvailable && c.preferences.style.capabilities.artworkCanvas) {
            c.slots.canvas(c.state.playback?.occurrenceId,c.surfaces.artwork && !hidden,Modifier.fillMaxSize())
        } else c.slots.artwork(c.state.playback?.occurrenceId,Modifier.fillMaxSize())
    }
}

private val doubleTapSeekStyles = setOf(
    PlayerDesignStyle.V1, PlayerDesignStyle.V2, PlayerDesignStyle.V3,
    PlayerDesignStyle.V4, PlayerDesignStyle.V6,
)

@Composable
private fun numberedTextColor(c: PlayerRenderContext): Color {
    val bg = c.preferences.style.resolveBackground(c.preferences.background)
    return if (bg == PlayerBackgroundStyle.DEFAULT && !c.preferences.style.capabilities.monochrome)
        MaterialTheme.colorScheme.onBackground else Color.White
}

@Composable
private fun numberedButtonColors(c: PlayerRenderContext, textColor: Color): Pair<Color, Color> {
    if (c.preferences.secondaryButtons)
        return MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val bg = c.preferences.style.resolveBackground(c.preferences.background)
    val icon = when {
        c.preferences.style.capabilities.monochrome -> Color.Black
        bg == PlayerBackgroundStyle.DEFAULT && !dark -> MaterialTheme.colorScheme.surface
        else -> Color.Black
    }
    return textColor to icon
}

@Composable
internal fun NumberedPlayer(c: PlayerRenderContext) {
    if (c.preferences.style == PlayerDesignStyle.V5) {
        LittlePlayerScreen(c)
        return
    }
    val textColor = numberedTextColor(c)
    if (c.landscape) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            NumberedArtwork(c, textColor, Modifier.weight(1f))
            NumberedControls(c, textColor, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            NumberedArtwork(c, textColor, Modifier.weight(1f).fillMaxWidth())
            NumberedControls(c, textColor, Modifier.fillMaxWidth())
        }
    }
}

/** Header plus the same square artwork geometry as Android, bounded by the available height. */
@Composable
private fun NumberedArtwork(c: PlayerRenderContext, textColor: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PlayingFromHeader(c, textColor, Modifier.padding(vertical = 4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight, if (LocalDesktopLayout.current) 392.dp else maxWidth)
            PlayerArtworkPager(
                c,
                Modifier.size(side).playerSeekDoubleTap(c, c.preferences.style in doubleTapSeekStyles),
            )
        }
    }
}

/** Title row + per-style top actions, slider + time label, then the per-style transport. */
@Composable
private fun NumberedControls(c: PlayerRenderContext, textColor: Color, modifier: Modifier) {
    val (buttonBg, buttonIcon) = numberedButtonColors(c, textColor)
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TitleLine(c, color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                ArtistLine(c, color = textColor, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
            }
            Spacer(Modifier.width(12.dp))
            NumberedTopActions(c, buttonBg, buttonIcon, textColor)
        }
        Spacer(Modifier.height(12.dp))
        PlayerSeek(c)
        Spacer(Modifier.height(12.dp))
        NumberedTransport(c)
        if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current &&
            c.preferences.style == PlayerDesignStyle.V4 && !c.landscape) {
            Spacer(Modifier.height(32.dp))
            CinematicPhoneBottomControls(c)
        }
    }
}

/** The phone Cinematic footer keeps Queue, Sleep and Lyrics reachable without opening More. */
@Composable
private fun CinematicPhoneBottomControls(c: PlayerRenderContext) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { c.interaction.panel = PlayerPanel.QUEUE },
            shape = RoundedCornerShape(50),
            modifier = Modifier.weight(1f).testTag("player-cinematic-queue"),
        ) {
            PlayerGlyph(PlayerSymbol.QUEUE, androidx.compose.material3.LocalContentColor.current, true, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.m5_queue))
        }
        PlayerIcon(PlayerSymbol.SLEEP, stringResource(actionLabel(PlayerAction.SLEEP)),
            { c.actions.onAction(PlayerAction.SLEEP) }, enabled = PlayerAction.SLEEP in c.state.actionSupport,
            modifier = Modifier.testTag("player-cinematic-sleep"), size = 48.dp, iconSize = 24.dp)
        FilledTonalButton(
            onClick = { c.interaction.panel = PlayerPanel.LYRICS },
            shape = RoundedCornerShape(50),
            modifier = Modifier.weight(1f).testTag("player-cinematic-lyrics"),
        ) {
            PlayerGlyph(PlayerSymbol.LYRICS, androidx.compose.material3.LocalContentColor.current, true, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.m5_lyrics))
        }
    }
}

@Composable
private fun TopActionBox(
    action: PlayerAction,
    c: PlayerRenderContext,
    size: Dp,
    iconSize: Dp,
    container: Color,
    iconColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
    selected: Boolean? = null,
) {
    // Only an action the host answers gets a control; an unsupported one would be a tap with no effect.
    val supported = action in c.state.actionSupport
    if (!supported && !moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current) return
    PlayerIcon(
        symbol = action.symbol2(),
        label = stringResource(actionLabel(action)),
        onClick = { c.actions.onAction(action) },
        size = size, iconSize = iconSize,
        container = container, contentColor = iconColor, shape = shape,
        selected = selected,
        enabled = supported,
    )
}

private fun PlayerAction.symbol2() = when (this) {
    PlayerAction.SHARE -> PlayerSymbol.SHARE
    PlayerAction.LIKE -> PlayerSymbol.HEART
    PlayerAction.MORE -> PlayerSymbol.MORE
    PlayerAction.SHUFFLE -> PlayerSymbol.SHUFFLE
    PlayerAction.REPEAT -> PlayerSymbol.REPEAT
    else -> PlayerSymbol.MORE
}

/** Per-style top actions next to the title block, ported from PlayerTopActions. */
@Composable
private fun NumberedTopActions(c: PlayerRenderContext, buttonBg: Color, buttonIcon: Color, textColor: Color) {
    val style = c.preferences.style
    val liked = c.state.liked
    val likeTint = if (liked) MaterialTheme.colorScheme.error else null
    when (style) {
        PlayerDesignStyle.V1 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopActionBox(PlayerAction.SHARE, c, 40.dp, 24.dp, buttonBg, buttonIcon, RoundedCornerShape(24.dp))
            TopActionBox(PlayerAction.MORE, c, 40.dp, 24.dp, buttonBg, buttonIcon, RoundedCornerShape(24.dp))
        }
        PlayerDesignStyle.V2 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopActionBox(PlayerAction.SHARE, c, 42.dp, 24.dp, buttonBg, buttonIcon,
                RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp, topEnd = 10.dp, bottomEnd = 10.dp))
            TopActionBox(PlayerAction.LIKE, c, 42.dp, 24.dp, buttonBg, buttonIcon,
                RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 50.dp, bottomEnd = 50.dp),
                selected = liked)
        }
        PlayerDesignStyle.V3, PlayerDesignStyle.V5 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopActionBox(PlayerAction.SHARE, c, 36.dp, 20.dp, Color.Transparent, textColor.copy(alpha = .7f), RoundedCornerShape(12.dp))
            TopActionBox(PlayerAction.LIKE, c, 36.dp, 20.dp, Color.Transparent,
                likeTint?.copy(alpha = .9f) ?: textColor.copy(alpha = .7f), RoundedCornerShape(12.dp), selected = liked)
        }
        PlayerDesignStyle.V4 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.slots.outputButton != null) {
                StyleIconButton(c, PlayerSymbol.OUTPUT, PlayerAction.OUTPUT, size = 44.dp, iconSize = 22.dp,
                    container = textColor.copy(alpha = .12f), iconColor = textColor, shape = RoundedCornerShape(14.dp))
            }
            TopActionBox(PlayerAction.SHARE, c, 44.dp, 22.dp, textColor.copy(alpha = .12f), textColor, RoundedCornerShape(14.dp))
            TopActionBox(PlayerAction.LIKE, c, 44.dp, 22.dp,
                if (liked) MaterialTheme.colorScheme.error.copy(alpha = .25f) else textColor.copy(alpha = .12f),
                likeTint ?: textColor, RoundedCornerShape(14.dp), selected = liked)
            TopActionBox(PlayerAction.MORE, c, 44.dp, 22.dp, textColor.copy(alpha = .12f), textColor, RoundedCornerShape(14.dp))
        }
        PlayerDesignStyle.V6 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopActionBox(PlayerAction.SHARE, c, 42.dp, 20.dp, textColor.copy(alpha = .12f), textColor,
                RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp, topEnd = 6.dp, bottomEnd = 6.dp))
            TopActionBox(PlayerAction.LIKE, c, 42.dp, 20.dp,
                if (liked) MaterialTheme.colorScheme.error.copy(alpha = .18f) else textColor.copy(alpha = .12f),
                likeTint ?: textColor, CircleShape, selected = liked)
            TopActionBox(PlayerAction.MORE, c, 42.dp, 20.dp, textColor.copy(alpha = .12f), textColor,
                RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 50.dp, bottomEnd = 50.dp))
        }
        else -> Unit
    }
}

/** Per-style transport cluster, ported from PlayerPlaybackControls. */
@Composable
internal fun NumberedTransport(c: PlayerRenderContext) {
    val textColor = numberedTextColor(c)
    val (buttonBg, buttonIcon) = numberedButtonColors(c, textColor)
    val playback = c.state.playback
    val playing = playback?.playWhenReady == true && playback.isEnded != true
    val liked = c.state.liked
    val repeatOn = c.state.repeat != PlayerRepeat.OFF
    when (c.preferences.style) {
        PlayerDesignStyle.V1, PlayerDesignStyle.V5 -> {
            val roundness by animateDpAsState(if (playing) 24.dp else 36.dp, tween(90), label = "v1PlayRound")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PlayerIcon(PlayerSymbol.REPEAT, stringResource(actionLabel(PlayerAction.REPEAT)),
                        { c.actions.onAction(PlayerAction.REPEAT) }, size = 32.dp, iconSize = 24.dp,
                        contentColor = textColor.copy(alpha = if (repeatOn) 1f else .5f), selected = repeatOn)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SkipButton(c, next = false, size = 32.dp, iconSize = 32.dp, iconColor = textColor)
                }
                Spacer(Modifier.width(8.dp))
                StylePlayButton(c, size = 72.dp, iconSize = 36.dp, container = buttonBg,
                    iconColor = buttonIcon, shape = RoundedCornerShape(roundness), spinnerSize = 36.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SkipButton(c, next = true, size = 32.dp, iconSize = 32.dp, iconColor = textColor)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PlayerIcon(PlayerSymbol.HEART, stringResource(Res.string.m5_like),
                        { c.actions.onAction(PlayerAction.LIKE) }, size = 32.dp, iconSize = 24.dp,
                        contentColor = if (liked) MaterialTheme.colorScheme.error else textColor, selected = liked)
                }
            }
        }
        PlayerDesignStyle.V2 -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val playHeight = maxWidth / 6f
            val playWidth = playHeight * 1.6f
            val sideHeight = playHeight * .8f
            val sideWidth = sideHeight * 1.3f
            Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                StyleIconButton(c, PlayerSymbol.PREVIOUS, PlayerAction.PREVIOUS,
                    size = sideHeight, iconSize = 32.dp, width = sideWidth, container = buttonBg, iconColor = buttonIcon,
                    shape = RoundedCornerShape(32.dp), enabled = c.state.canPrevious)
                Spacer(Modifier.width(16.dp))
                StylePlayButton(c, size = playHeight, iconSize = 42.dp, width = playWidth, container = buttonBg,
                    iconColor = buttonIcon, shape = RoundedCornerShape(32.dp), spinnerSize = 42.dp)
                Spacer(Modifier.width(16.dp))
                StyleIconButton(c, PlayerSymbol.NEXT, PlayerAction.NEXT,
                    size = sideHeight, iconSize = 32.dp, width = sideWidth, container = buttonBg, iconColor = buttonIcon,
                    shape = RoundedCornerShape(32.dp), enabled = c.state.canNext)
            }
        }
        PlayerDesignStyle.V3 -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            PlayerIcon(PlayerSymbol.SHUFFLE, stringResource(actionLabel(PlayerAction.SHUFFLE)),
                { c.actions.onAction(PlayerAction.SHUFFLE) }, size = 40.dp, iconSize = 22.dp,
                contentColor = textColor.copy(alpha = if (c.state.shuffle) 1f else .4f), selected = c.state.shuffle)
            PlayerIcon(PlayerSymbol.PREVIOUS, stringResource(actionLabel(PlayerAction.PREVIOUS)),
                { c.actions.onAction(PlayerAction.PREVIOUS) }, enabled = c.state.canPrevious,
                size = 52.dp, iconSize = 26.dp, container = textColor.copy(alpha = .08f),
                contentColor = textColor.copy(alpha = if (c.state.canPrevious) .9f else .4f),
                shape = RoundedCornerShape(14.dp))
            StylePlayButton(c, size = 70.dp, iconSize = 34.dp, container = buttonBg,
                iconColor = buttonIcon, shape = CircleShape, spinnerSize = 32.dp)
            PlayerIcon(PlayerSymbol.NEXT, stringResource(actionLabel(PlayerAction.NEXT)),
                { c.actions.onAction(PlayerAction.NEXT) }, enabled = c.state.canNext,
                size = 52.dp, iconSize = 26.dp, container = textColor.copy(alpha = .08f),
                contentColor = textColor.copy(alpha = if (c.state.canNext) .9f else .4f),
                shape = RoundedCornerShape(14.dp))
            PlayerIcon(PlayerSymbol.REPEAT, stringResource(actionLabel(PlayerAction.REPEAT)),
                { c.actions.onAction(PlayerAction.REPEAT) }, size = 40.dp, iconSize = 22.dp,
                contentColor = textColor.copy(alpha = if (repeatOn) 1f else .4f), selected = repeatOn)
        }
        PlayerDesignStyle.V4 -> {
            val corner by animateDpAsState(
                if (playing) 28.dp else 44.dp,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "v4PlayCorner")
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val baseLarge = 56.dp
                val baseSmall = 46.dp
                val baseGap = 12.dp
                val centerSize = 88.dp
                val sideTotal = (maxWidth - centerSize - 40.dp) / 2f
                val scale = ((sideTotal - baseGap) / (baseLarge + baseSmall)).coerceIn(.6f, 1f)
                val large = baseLarge * scale
                val small = baseSmall * scale
                val gap = baseGap * scale
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), Arrangement.End, Alignment.CenterVertically) {
                        PlayerIcon(PlayerSymbol.SHUFFLE, stringResource(actionLabel(PlayerAction.SHUFFLE)),
                            { c.actions.onAction(PlayerAction.SHUFFLE) }, size = small, iconSize = 22.dp * scale,
                            container = textColor.copy(alpha = if (c.state.shuffle) .2f else .08f),
                            contentColor = textColor.copy(alpha = if (c.state.shuffle) 1f else .6f),
                            shape = RoundedCornerShape(16.dp * scale), selected = c.state.shuffle)
                        Spacer(Modifier.width(gap))
                        PlayerIcon(PlayerSymbol.PREVIOUS, stringResource(actionLabel(PlayerAction.PREVIOUS)),
                            { c.actions.onAction(PlayerAction.PREVIOUS) }, enabled = c.state.canPrevious,
                            size = large, iconSize = 28.dp * scale,
                            container = textColor.copy(alpha = .15f),
                            contentColor = textColor.copy(alpha = if (c.state.canPrevious) 1f else .4f),
                            shape = RoundedCornerShape(18.dp * scale))
                    }
                    StylePlayButton(c, size = centerSize, iconSize = 44.dp, container = buttonBg,
                        iconColor = buttonIcon, shape = RoundedCornerShape(corner), spinnerSize = 40.dp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                    Row(Modifier.weight(1f), Arrangement.Start, Alignment.CenterVertically) {
                        PlayerIcon(PlayerSymbol.NEXT, stringResource(actionLabel(PlayerAction.NEXT)),
                            { c.actions.onAction(PlayerAction.NEXT) }, enabled = c.state.canNext,
                            size = large, iconSize = 28.dp * scale,
                            container = textColor.copy(alpha = .15f),
                            contentColor = textColor.copy(alpha = if (c.state.canNext) 1f else .4f),
                            shape = RoundedCornerShape(18.dp * scale))
                        Spacer(Modifier.width(gap))
                        PlayerIcon(PlayerSymbol.REPEAT, stringResource(actionLabel(PlayerAction.REPEAT)),
                            { c.actions.onAction(PlayerAction.REPEAT) }, size = small, iconSize = 22.dp * scale,
                            container = textColor.copy(alpha = if (repeatOn) .2f else .08f),
                            contentColor = textColor.copy(alpha = if (repeatOn) 1f else .6f),
                            shape = RoundedCornerShape(16.dp * scale), selected = repeatOn)
                    }
                }
            }
        }
        PlayerDesignStyle.V6 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = textColor.copy(alpha = .08f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(6.dp), Arrangement.Center, Alignment.CenterVertically) {
                    Surface(
                        onClick = { c.actions.onAction(PlayerAction.PREVIOUS) },
                        enabled = c.state.canPrevious,
                        shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PlayerIcon(PlayerSymbol.PREVIOUS, stringResource(actionLabel(PlayerAction.PREVIOUS)),
                                { c.actions.onAction(PlayerAction.PREVIOUS) }, enabled = c.state.canPrevious,
                                iconSize = 28.dp,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                    alpha = if (c.state.canPrevious) 1f else .4f))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = c.actions.playPause,
                        shape = RoundedCornerShape(28.dp),
                        color = buttonBg,
                        modifier = Modifier.size(width = 88.dp, height = 80.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val symbol = when {
                                playback?.isEnded == true -> PlayerSymbol.REPLAY
                                playing -> PlayerSymbol.PAUSE
                                else -> PlayerSymbol.PLAY
                            }
                            val playMotion = rememberPlayerControlMotion(PlayerControlKind.PlayPause, symbol, c.motionActive)
                            if (playback?.isBuffering == true) {
                                CircularProgressIndicator(Modifier.size(40.dp), color = buttonIcon)
                            } else {
                                PlayerControlCrossfade(symbol, c.motionActive, Modifier.size(44.dp).then(playMotion.modifier)) {
                                    Canvas(Modifier.size(44.dp)) { drawGlyphFor(it, buttonIcon) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = { c.actions.onAction(PlayerAction.NEXT) },
                        enabled = c.state.canNext,
                        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PlayerIcon(PlayerSymbol.NEXT, stringResource(actionLabel(PlayerAction.NEXT)),
                                { c.actions.onAction(PlayerAction.NEXT) }, enabled = c.state.canNext,
                                iconSize = 28.dp,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                    alpha = if (c.state.canNext) 1f else .4f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                Surface(
                    onClick = { c.actions.onAction(PlayerAction.SHUFFLE) },
                    shape = CircleShape,
                    color = if (c.state.shuffle) MaterialTheme.colorScheme.tertiaryContainer
                        else textColor.copy(alpha = .08f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PlayerIcon(PlayerSymbol.SHUFFLE, stringResource(actionLabel(PlayerAction.SHUFFLE)),
                            { c.actions.onAction(PlayerAction.SHUFFLE) }, iconSize = 20.dp,
                            contentColor = if (c.state.shuffle) MaterialTheme.colorScheme.onTertiaryContainer
                                else textColor.copy(alpha = .5f), selected = c.state.shuffle)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Surface(
                    onClick = { c.actions.onAction(PlayerAction.REPEAT) },
                    shape = CircleShape,
                    color = if (repeatOn) MaterialTheme.colorScheme.tertiaryContainer else textColor.copy(alpha = .08f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PlayerIcon(PlayerSymbol.REPEAT, stringResource(actionLabel(PlayerAction.REPEAT)),
                            { c.actions.onAction(PlayerAction.REPEAT) }, iconSize = 20.dp,
                            contentColor = if (repeatOn) MaterialTheme.colorScheme.onTertiaryContainer
                                else textColor.copy(alpha = .5f), selected = repeatOn)
                    }
                }
            }
        }
        else -> NativeTransport(c)
    }
}

/** Full-screen V5: progress fills top-down behind the little-player content and is itself the seek surface. */
@Composable
internal fun LittlePlayerScreen(c: PlayerRenderContext) {
    val duration = c.state.playback?.durationMs ?: 0L
    val position = (c.interaction.previewMs ?: c.state.positionMs).coerceAtLeast(0)
    val fraction = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val seekLabel = stringResource(Res.string.m5_seek)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer)) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction)
                .align(Alignment.TopStart)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .28f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = seekLabel
                    if (duration > 0) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    // The little player fits at every scale, so nothing scrolls; this action only
                    // carries the scroll semantics the seek contract expects. It must sit on the
                    // unrotated container: portrait content is placed rotated (LandscapeLikeBox), and
                    // scroll-into-view math does not converge under that transform.
                    scrollBy { _, _ -> true }
                }
                .littlePlayerGestures(c, fraction),
        ) {
            if (c.landscape) LittlePlayerContent(c)
            else LandscapeLikeBox(Modifier.fillMaxSize()) { LittlePlayerContent(c) }
        }
    }
}

/** Drag down from the progress region scrubs; double-tap top/bottom skips previous/next. */
private fun Modifier.littlePlayerGestures(c: PlayerRenderContext, fraction: Float): Modifier = this
    .pointerInput(c, fraction) {
        var seeking = false
        val region = maxOf(fraction * size.height, 24.dp.toPx())
        detectVerticalDragGestures(
            onDragStart = { o ->
                val duration = c.state.playback?.durationMs
                seeking = c.state.canSeek && c.state.playback?.isSeekable == true &&
                    duration != null && duration > 0 && o.y <= region
                if (seeking) c.interaction.preview((o.y / size.height).coerceIn(0f, 1f), c.state)
            },
            onVerticalDrag = { change, _ ->
                if (seeking) {
                    change.consume()
                    c.interaction.preview((change.position.y / size.height).coerceIn(0f, 1f), c.state)
                }
            },
            onDragEnd = { if (seeking) c.interaction.commit(c.state, c.actions.seekTo); seeking = false },
            onDragCancel = { c.interaction.cancelPreview(); seeking = false },
        )
    }
    .pointerInput(c) {
        detectTapGestures(onDoubleTap = { o ->
            c.actions.onAction(if (o.y >= size.height / 2f) PlayerAction.NEXT else PlayerAction.PREVIOUS)
        })
    }

@Composable
private fun LittlePlayerContent(c: PlayerRenderContext) {
    val textColor = MaterialTheme.colorScheme.onPrimaryContainer
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val titleColor = textColor.copy(alpha = .95f)
        val secondaryColor = textColor.copy(alpha = .6f)
        val timeColor = textColor.copy(alpha = .85f)
        val scale = minOf(maxWidth / 420.dp, maxHeight / 260.dp).coerceIn(.78f, 1.15f)
        val titleSize = 56f * scale
        val timeSize = 44f * scale
        val iconSize = 26.dp * scale
        val collapseIconSize = 28.dp * scale
        val position = (c.interaction.previewMs ?: c.state.positionMs).coerceAtLeast(0)
        val duration = c.state.playback?.durationMs
        val timeText = if (duration == null || duration <= 0) playerTime(position)
            else "${playerTime(position)}/${playerTime(duration)}"
        Column(Modifier.fillMaxSize().padding(horizontal = (18.dp * scale), vertical = (10.dp * scale))) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    MarqueeText(
                        c.state.title,
                        Modifier.fillMaxWidth(),
                        color = titleColor,
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = titleSize.sp),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp * scale))
                    c.state.albumTitle?.takeIf { it.isNotBlank() }?.let { album ->
                        MarqueeText(album, Modifier.fillMaxWidth(), color = secondaryColor,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (c.state.artist.isNotBlank()) {
                        MarqueeText("by - ${c.state.artist}", Modifier.fillMaxWidth(), color = secondaryColor,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.width(16.dp * scale))
                Text(
                    timeText,
                    color = timeColor,
                    fontSize = timeSize.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 140.dp * scale),
                )
            }
            Spacer(Modifier.height(14.dp * scale))
            Spacer(Modifier.height(6.dp * scale))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(PlayerSymbol.DOWN, stringResource(Res.string.m5_collapse),
                    { c.actions.onAction(PlayerAction.COLLAPSE) },
                    size = collapseIconSize, iconSize = collapseIconSize,
                    contentColor = textColor.copy(alpha = .8f))
                Spacer(Modifier.weight(1f))
                PlayerIcon(PlayerSymbol.HEART, stringResource(Res.string.m5_like),
                    { c.actions.onAction(PlayerAction.LIKE) },
                    size = iconSize, iconSize = iconSize,
                    contentColor = if (c.state.liked) MaterialTheme.colorScheme.error.copy(alpha = .9f)
                        else textColor.copy(alpha = .78f), selected = c.state.liked)
                Spacer(Modifier.width(18.dp * scale))
                PlayerIcon(PlayerSymbol.QUEUE, stringResource(Res.string.m5_queue),
                    { c.interaction.panel = PlayerPanel.QUEUE },
                    size = iconSize, iconSize = iconSize,
                    contentColor = textColor.copy(alpha = .78f))
                Spacer(Modifier.width(18.dp * scale))
                PlayerIcon(PlayerSymbol.SHARE, stringResource(Res.string.m5_share),
                    { c.actions.onAction(PlayerAction.SHARE) },
                    size = iconSize, iconSize = iconSize,
                    contentColor = textColor.copy(alpha = .78f))
                Spacer(Modifier.width(18.dp * scale))
                PlayerIcon(PlayerSymbol.NEXT, stringResource(Res.string.m5_next),
                    { c.actions.onAction(PlayerAction.NEXT) },
                    enabled = c.state.canNext,
                    size = iconSize, iconSize = iconSize,
                    contentColor = textColor.copy(alpha = .78f))
                Spacer(Modifier.width(18.dp * scale))
                PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.m5_more),
                    { c.actions.onAction(PlayerAction.MORE) },
                    size = iconSize, iconSize = iconSize,
                    contentColor = textColor.copy(alpha = .78f))
            }
        }
    }
}

/** Lays content out with swapped constraints then rotates it 90°, exactly like the original portrait V5. */
@Composable
private fun LandscapeLikeBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.graphicsLayer { clip = true }) { measurables, constraints ->
        val measurable = measurables.firstOrNull()
        if (measurable == null) {
            layout(constraints.minWidth, constraints.minHeight) {}
        } else {
            val swapped = Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
            val placeable = measurable.measure(swapped)
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val rotatedWidth = placeable.height
            val rotatedHeight = placeable.width
            val x = ((width - rotatedWidth) / 2).coerceAtLeast(0)
            val y = ((height - rotatedHeight) / 2).coerceAtLeast(0)
            layout(width, height) {
                placeable.placeWithLayer(x, y) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    rotationZ = 90f
                    translationX = placeable.height.toFloat()
                }
            }
        }
    }
}

/** Fallback prev/play/next row kept for looks that share the plain transport. */
@Composable
internal fun NativeTransport(c: PlayerRenderContext, square: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ActionButton(PlayerAction.PREVIOUS, c)
        moe.rukamori.archivetune.shared.ui.player.PlayButton(c, square = square)
        ActionButton(PlayerAction.NEXT, c)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyphFor(symbol: PlayerSymbol, color: Color) {
    val w = size.width
    val h = size.height
    when (symbol) {
        PlayerSymbol.PAUSE -> {
            drawLine(color, androidx.compose.ui.geometry.Offset(w * .33f, h * .2f), androidx.compose.ui.geometry.Offset(w * .33f, h * .8f), w * .085f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(w * .67f, h * .2f), androidx.compose.ui.geometry.Offset(w * .67f, h * .8f), w * .085f, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        else -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .3f, h * .2f); lineTo(w * .8f, h * .5f); lineTo(w * .3f, h * .8f); close()
            }
            drawPath(path, color)
        }
    }
}
