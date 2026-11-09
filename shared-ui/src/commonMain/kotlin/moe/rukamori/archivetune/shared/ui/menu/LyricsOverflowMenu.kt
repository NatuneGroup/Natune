package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Android's lyrics actions: Apple popup rows and hairlines, or the explicit Material list sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOverflowMenu(actions: List<OverflowAction>, style: PlayerMenuStyle, onDismiss: () -> Unit, onFailure: () -> Unit) {
    val scope = rememberCoroutineScope()
    val anchor = LocalOverflowMenuAnchor.current
    fun perform(action: OverflowAction) {
        if (!action.enabled) return
        scope.launch {
            try { action.run(); if (action.dismissOnSuccess) onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    val body: @Composable () -> Unit = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                ListItem(headlineContent = { Text(stringResource(action.label), color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = action.description?.let { title -> { Text(stringResource(title)) } },
                    trailingContent = { Icon(painterResource(phoneMenuIcon(action.id)), null, Modifier.size(20.dp)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = action.enabled) { perform(action) })
                if (index < actions.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            }
        }
    }
    if (style == PlayerMenuStyle.APPLE_MUSIC) {
        val position = remember(anchor) { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val bounds = anchor?.bounds
                val right = bounds?.right?.roundToInt() ?: anchorBounds.right
                val left = bounds?.left?.roundToInt() ?: anchorBounds.left
                val top = bounds?.top?.roundToInt() ?: anchorBounds.top
                val bottom = bounds?.bottom?.roundToInt() ?: anchorBounds.bottom
                val x = if (layoutDirection == LayoutDirection.Rtl) left else right - popupContentSize.width
                val y = if (bottom + popupContentSize.height <= windowSize.height) bottom else top - popupContentSize.height
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        } }
        Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Surface(Modifier.width(220.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp, content = body)
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss) { body() }
}
