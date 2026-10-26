/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassRole
import moe.rukamori.archivetune.shared.ui.glass.PlatformBackdrop
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The message whose anchored popup is open. [bounds] is the bubble's position in the root, so the popup opens beside it. */
internal data class CommentActionTarget(val message: CommentMessageUi, val bounds: Rect)

private val CommentQuickReactions = listOf("\u2764\uFE0F", "👍", "😂", "😮", "😢", "🔥")

/**
 * Action popup anchored to a long-pressed bubble: quick reactions, the full emoji entry and the reply, copy,
 * edit, pin and delete actions. The glass samples [backdrop], which the screen records from the chat content
 * only while this popup is open. The popup is a sibling of that content, so the sample is a one-way read and
 * cannot recurse into the layer being drawn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentActionsPopup(
    target: CommentActionTarget,
    isHost: Boolean,
    backdrop: PlatformBackdrop?,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onPinToggle: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isMe = target.message.author.own

    var dismissed by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.35f) }
    val alphaAnim = remember { Animatable(0f) }

    var overlayWidthPx by remember { mutableIntStateOf(0) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    var popupWidthPx by remember { mutableIntStateOf(0) }
    var popupHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(1f, tween(180)) }
        scaleJob.join()
        alphaJob.join()
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(0.35f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(0f, tween(160)) }
        scaleJob.join()
        alphaJob.join()
        onDismiss()
    }

    fun dismissAfter(action: () -> Unit) {
        action()
        if (!dismissed) dismissed = true
    }

    fun popupPlacement(): Pair<IntOffset, Boolean> {
        val marginPx = with(density) { 12.dp.toPx() }.toInt()
        val estimatedHeight = if (popupHeightPx > 0) popupHeightPx else with(density) { 112.dp.toPx() }.toInt()
        val estimatedWidth = if (popupWidthPx > 0) popupWidthPx else with(density) { 300.dp.toPx() }.toInt()
        val screenWidthPx = if (overlayWidthPx > 0) overlayWidthPx else estimatedWidth + 2 * marginPx
        val maxX = (screenWidthPx - estimatedWidth - marginPx).coerceAtLeast(marginPx)
        val rawX =
            if (isMe) {
                (target.bounds.right.toInt() - estimatedWidth - 8).coerceAtLeast(marginPx)
            } else {
                (target.bounds.left.toInt() + 8).coerceAtMost(screenWidthPx - estimatedWidth - marginPx)
            }
        val x = rawX.coerceIn(marginPx, maxX)
        val below = target.bounds.bottom + 6 < overlayHeightPx - estimatedHeight - marginPx
        val y =
            if (below) {
                target.bounds.bottom.toInt() + 6
            } else {
                (target.bounds.top.toInt() - estimatedHeight - 6).coerceAtLeast(marginPx)
            }
        return IntOffset(x, y.coerceAtLeast(marginPx)) to below
    }

    val scrimAlpha = 0.25f * alphaAnim.value
    val popupShape = RoundedCornerShape(18.dp)
    // Glass when a backdrop is recorded; otherwise the flat dark menu. The rim is shared by both looks.
    val menuSurface = Modifier
        .liquidGlassMaterial(
            backdrop = backdrop,
            shape = popupShape,
            role = LiquidGlassRole.ActionMenu,
            fallbackColor = Color(0xF226262B),
            interactive = true,
        )
        .border(1.dp, Color.White.copy(alpha = 0.12f), popupShape)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                overlayWidthPx = size.width
                overlayHeightPx = size.height
            }
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!dismissed) dismissed = true },
            ),
    ) {
        val (offset, opensBelow) = popupPlacement()

        Column(
            modifier = Modifier
                .offset { offset }
                .onSizeChanged { size ->
                    popupWidthPx = size.width
                    popupHeightPx = size.height
                }
                .widthIn(max = 320.dp)
                .graphicsLayer {
                    this.alpha = alphaAnim.value
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.transformOrigin = TransformOrigin(if (isMe) 0.92f else 0.08f, if (opensBelow) 0f else 1f)
                    this.shadowElevation = 18.dp.toPx()
                    this.shape = popupShape
                    this.clip = false
                }
                .clip(popupShape)
                .then(menuSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CommentQuickReactions.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    onReact(emoji)
                                    if (!dismissed) dismissed = true
                                },
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 22.sp)
                    }
                }
                // Opens the full sheet and leaves this popup in place; picking an emoji there acts on this target.
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenEmojiPicker,
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.m4c_icon_add),
                        contentDescription = stringResource(Res.string.m4c_all_emojis),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.14f)),
            )

            // A FlowRow so the delete chips wrap onto a second line instead of overflowing the 320dp popup.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CommentPopupActionChip(icon = Res.drawable.m4c_icon_reply, label = stringResource(Res.string.m4c_reply)) {
                    dismissAfter(onReply)
                }
                CommentPopupActionChip(icon = Res.drawable.m4c_icon_copy, label = stringResource(Res.string.m4c_copy)) {
                    dismissAfter(onCopy)
                }
                if (isMe) {
                    CommentPopupActionChip(icon = Res.drawable.m4c_icon_edit, label = stringResource(Res.string.m4c_edit)) {
                        dismissAfter(onEdit)
                    }
                }
                CommentPopupActionChip(
                    icon = Res.drawable.m4c_icon_push_pin,
                    label = stringResource(if (target.message.pinned) Res.string.m4c_unpin else Res.string.m4c_pin),
                ) {
                    dismissAfter(onPinToggle)
                }
                if (isMe) {
                    // Your own message only ever deletes room-wide.
                    CommentPopupActionChip(icon = Res.drawable.m4c_icon_delete, label = stringResource(Res.string.m4c_delete)) {
                        dismissAfter(onDeleteForEveryone)
                    }
                } else {
                    // Someone else's message: a private hide, plus a room-wide moderation delete for the host.
                    CommentPopupActionChip(icon = Res.drawable.m4c_icon_delete, label = stringResource(Res.string.m4c_delete)) {
                        dismissAfter(onDeleteForMe)
                    }
                    if (isHost) {
                        CommentPopupActionChip(
                            icon = Res.drawable.m4c_icon_delete_forever,
                            label = stringResource(Res.string.m4c_delete_for_everyone),
                        ) {
                            dismissAfter(onDeleteForEveryone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentPopupActionChip(icon: DrawableResource, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.10f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}
