package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.listentogether.RepliedMessage
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MaxTypingAvatars = 5
private const val TypingCycleMillis = 900
private const val PinnedDragLimitDp = 96
private const val PinnedCommitDp = 48
private val CommentLinkPattern = Regex("(https?://[^\\s<>\"]+)")

/** mm:ss, or h:mm:ss from one hour, for a track duration in milliseconds. Blank when the length is unknown. */
internal fun formatCommentTrackDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return if (minutes >= 60L) {
        "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$seconds"
    } else {
        "$minutes:$seconds"
    }
}

/**
 * Message text with every http(s) link tappable. The host decides where a link opens, so the screen never
 * launches a raw remote URL itself.
 */
internal fun commentFormatMessageWithLinks(
    text: String,
    linkColor: Color,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    CommentLinkPattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val url = match.value
        withLink(
            LinkAnnotation.Clickable(
                url,
                styles = TextLinkStyles(
                    SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { onLink(url) },
            ),
        ) { append(url) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

/** Pinned order: the most recent pin first. Falls back to the message time for pins without a pin time. */
private fun commentPinnedRank(message: CommentMessageUi): Long =
    if (message.pinnedAt > 0L) message.pinnedAt else message.timestamp

private fun commentPinnedPreview(message: CommentMessageUi?, deletedLabel: String): String {
    if (message == null) return ""
    if (message.deleted) return deletedLabel
    val track = message.sharedTrack
    if (track != null) return "♪ ${track.title} • ${track.artist}"
    return message.text
}

/**
 * One chat row: avatar, the author label for incoming rows, the bubble with its swipe-to-reply and long-press
 * anchor, reactions and the time line. [onLongPress] receives the bubble bounds in root coordinates.
 */
@Composable
internal fun CommentMessageRow(
    message: CommentMessageUi,
    canReply: Boolean,
    interactive: Boolean,
    highlighted: Boolean,
    reactionsEnabled: Boolean,
    avatar: CommentAvatar,
    thumbnail: CommentThumbnail,
    onReply: () -> Unit,
    onLongPress: (Rect) -> Unit,
    onReact: (String) -> Unit,
    onPlay: (TrackInfo) -> Unit,
    onLink: (String) -> Unit,
) {
    val own = message.author.own
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxSwipePx = with(density) { 130.dp.toPx() }
    val scope = rememberCoroutineScope()
    val replyNow by rememberUpdatedState(onReply)
    val swipe = remember(message.id) { Animatable(0f) }
    var bubbleBounds by remember(message.id) { mutableStateOf(Rect.Zero) }
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 0.35f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "comment-highlight",
    )
    val linkColor = MaterialTheme.colorScheme.primary
    val swipeModifier = if (!canReply) {
        Modifier
    } else {
        Modifier.pointerInput(message.id) {
            detectHorizontalDragGestures(
                onDragCancel = { scope.launch { swipe.animateTo(0f, spring()) } },
                onDragEnd = {
                    val released = swipe.value
                    scope.launch { swipe.animateTo(0f, spring()) }
                    if (released >= thresholdPx) replyNow()
                },
            ) { change, amount ->
                change.consume()
                scope.launch { swipe.snapTo((swipe.value + amount).coerceIn(0f, maxSwipePx)) }
            }
        }
    }
    val longPressModifier = if (interactive && !message.deleted) {
        Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(bubbleBounds) })
    } else {
        Modifier
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (own) 16.dp else 4.dp,
        bottomEnd = if (own) 4.dp else 16.dp,
    )
    val bubbleColor = if (own) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha)

    Box(modifier = Modifier.fillMaxWidth()) {
        if (canReply) {
            // The reply hint sits behind the bubble and fades in as the bubble is dragged toward the threshold.
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_reply),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(20.dp)
                    .graphicsLayer { alpha = (swipe.value / thresholdPx).coerceIn(0f, 1f) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.value.roundToInt(), 0) },
            horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!own) {
                avatar(message.author, Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.widthIn(max = 300.dp),
                horizontalAlignment = if (own) Alignment.End else Alignment.Start,
            ) {
                if (!own) {
                    Text(
                        text = message.author.username,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { bubbleBounds = it.boundsInRoot() }
                        .clip(bubbleShape)
                        .background(bubbleColor)
                        .background(highlightColor)
                        .then(swipeModifier)
                        .then(longPressModifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (message.deleted) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.m4c_icon_delete),
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.m4c_message_deleted),
                                style = MaterialTheme.typography.labelMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        message.replyTo?.let { CommentRepliedPreview(replyTo = it) }
                        Text(
                            text = commentFormatMessageWithLinks(message.text, linkColor, onLink),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        message.sharedTrack?.let { track ->
                            CommentSharedTrackCard(
                                track = track,
                                thumbnail = thumbnail,
                                onPlay = { onPlay(track) },
                            )
                        }
                    }
                }
                if (!message.deleted && message.reactions.isNotEmpty()) {
                    CommentReactionsRow(
                        reactions = message.reactions,
                        enabled = reactionsEnabled,
                        onToggle = onReact,
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (message.pinned) {
                        Icon(
                            painter = painterResource(Res.drawable.m4c_icon_push_pin),
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (message.edited && !message.deleted) {
                        Text(
                            text = stringResource(Res.string.m4c_edited),
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = message.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (own) {
                Spacer(Modifier.width(8.dp))
                avatar(message.author, Modifier.size(28.dp))
            }
        }
    }
}

/** The quoted message shown above a reply, with the author and a two-line excerpt. */
@Composable
internal fun CommentRepliedPreview(replyTo: RepliedMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.8f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = replyTo.username,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = replyTo.message,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Reaction chips under a bubble. Tapping one toggles it through the host; the mine tint marks the local user's emoji. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentReactionsRow(
    reactions: List<CommentReactionUi>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            Surface(
                onClick = { onToggle(reaction.emoji) },
                enabled = enabled,
                shape = RoundedCornerShape(50),
                color = if (reaction.mine) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                border = if (reaction.mine) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                } else {
                    null
                },
            ) {
                Text(
                    text = if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** A shared song inside a bubble. Tapping it asks the host to start the track in the room. */
@Composable
internal fun CommentSharedTrackCard(
    track: TrackInfo,
    thumbnail: CommentThumbnail,
    onPlay: () -> Unit,
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail(track.thumbnail, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = formatCommentTrackDuration(track.duration)
                if (duration.isNotEmpty()) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_solar_play_linear),
                contentDescription = stringResource(Res.string.m4c_play_song),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Marks where restored history ends and live messages begin. */
@Composable
internal fun CommentOlderMessagesDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(Res.drawable.m4c_icon_history),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(Res.string.m4c_older_messages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Typing indicator: overlapping member avatars, three bouncing dots on a 900 ms cycle, then the label and the
 * member names. The caller only composes this while someone is typing.
 */
@Composable
internal fun CommentTypingRow(
    typing: List<CommentAuthorUi>,
    avatar: CommentAvatar,
    modifier: Modifier = Modifier,
) {
    val shown = typing.take(MaxTypingAvatars)
    // With motion off the dots hold the cycle start, so no frame clock runs behind a hidden or minimized window.
    val transition = if (motionAllowed()) rememberInfiniteTransition(label = "comment-typing") else null
    val phase = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TypingCycleMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "comment-typing-phase",
    )?.value ?: 0f
    val names = when (typing.size) {
        1 -> typing[0].username
        2 -> "${typing[0].username} & ${typing[1].username}"
        else -> "${typing.take(2).joinToString { it.username }}…"
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp + 13.dp * (shown.size - 1).coerceAtLeast(0))
                .height(20.dp),
        ) {
            shown.forEachIndexed { index, author ->
                avatar(
                    author,
                    Modifier
                        .offset(x = 13.dp * index)
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                val lift = sin(((phase + index * 0.2f) * 2f * PI.toFloat()).toDouble()).toFloat().coerceAtLeast(0f)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .offset(y = (-lift * 3f).dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.65f * lift)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.m4c_typing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = names,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Pinned carousel: the displayed pin stays until another pin arrives or it is removed. Tap steps to the next pin
 * and jumps to it; a horizontal drag steps once it passes the commit distance and otherwise springs back.
 * Composed only while [pinned] holds at least one message, so the caller guards the empty case.
 */
@Composable
internal fun CommentPinnedStack(
    pinned: List<CommentMessageUi>,
    onJump: (String) -> Unit,
    onUnpin: (String) -> Unit,
) {
    val ordered = remember(pinned) { pinned.sortedByDescending(::commentPinnedRank) }
    val keys = ordered.map { it.id }
    val seenKeys = remember { mutableStateOf<Set<String>>(emptySet()) }
    var displayedId by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragLimitPx = with(density) { PinnedDragLimitDp.dp.toPx() }
    val commitPx = with(density) { PinnedCommitDp.dp.toPx() }
    val drag = remember { Animatable(0f) }
    val deletedLabel = stringResource(Res.string.m4c_message_deleted)

    LaunchedEffect(keys) {
        val fresh = keys.firstOrNull { it !in seenKeys.value }
        seenKeys.value = keys.toSet()
        val current = displayedId
        displayedId = when {
            fresh != null -> fresh
            current != null && keys.contains(current) -> current
            else -> keys.firstOrNull()
        }
    }

    val index = ordered.indexOfFirst { it.id == displayedId }.coerceAtLeast(0)
    val shown = ordered[index]

    fun stepTo(delta: Int) {
        if (ordered.size < 2) return
        direction = if (delta >= 0) 1 else -1
        displayedId = ordered[(index + delta + ordered.size) % ordered.size].id
    }

    Surface(
        onClick = {
            val target = ordered[(index + 1) % ordered.size]
            stepTo(1)
            onJump(target.id)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(52.dp)
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            .pointerInput(keys, displayedId) {
                detectHorizontalDragGestures(
                    onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                    onDragEnd = {
                        val released = drag.value
                        scope.launch { drag.animateTo(0f, spring()) }
                        if (abs(released) >= commitPx) stepTo(if (released < 0f) 1 else -1)
                    },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceIn(-dragLimitPx, dragLimitPx)) }
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_push_pin),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                targetState = shown.id,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { width -> width * direction },
                    ) + fadeIn(animationSpec = tween(220))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(220),
                            targetOffsetX = { width -> -width * direction },
                        ) + fadeOut(animationSpec = tween(220)))
                },
                label = "comment-pinned-text",
            ) { pinnedId ->
                Text(
                    text = commentPinnedPreview(ordered.firstOrNull { it.id == pinnedId }, deletedLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ordered.size > 1) {
                Text(
                    text = stringResource(Res.string.m4c_pinned_position, index + 1, ordered.size),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(
                onClick = { onUnpin(shown.id) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_close),
                    contentDescription = stringResource(Res.string.m4c_unpin),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
