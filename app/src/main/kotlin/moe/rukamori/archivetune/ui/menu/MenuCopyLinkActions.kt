package moe.rukamori.archivetune.ui.menu

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.ui.menu.shareLinkAtPosition
import moe.rukamori.archivetune.ui.component.NewAction

/** The grid tile shape every player and song menu action shares: a 28 dp glyph over a label. */
internal fun menuGridAction(
    @DrawableRes iconRes: Int,
    text: String,
    onClick: () -> Unit,
): NewAction =
    NewAction(
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        text = text,
        onClick = onClick,
    )

/** The copy-link grid row shown beside share in every menu that has a link to copy. */
internal fun copyLinkMenuAction(text: String, onClick: () -> Unit): NewAction =
    menuGridAction(R.drawable.link, text, onClick)

/**
 * The playing track's timestamped link: [url] with the live position appended as YouTube's `t`
 * parameter, read at click time so the tile always copies the position it was tapped at.
 */
internal fun copyLinkAtCurrentTimeAction(
    text: String,
    context: Context,
    url: String,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
): NewAction =
    copyLinkMenuAction(text) {
        context.copyLinkToClipboard(shareLinkAtPosition(url, positionMs()))
        onDismiss()
    }

/** The section ListItem shape used below the grid: leading icon, label, transparent background. */
@Composable
internal fun MenuLinkListItem(
    @DrawableRes iconRes: Int,
    text: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = text) },
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
