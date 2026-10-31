@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

@Composable
internal fun QueueSelectionFloatingToolbar(
    allSelected: Boolean,
    pureBlack: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val toolbarContainerColor = if (pureBlack) Color.Black else colorScheme.surfaceContainerHigh
    val toolbarContentColor = if (pureBlack) Color.White else colorScheme.onSurface
    val fabContainerColor = if (pureBlack) Color.White.copy(alpha = 0.12f) else colorScheme.surfaceContainerHighest
    val fabContentColor = if (pureBlack) Color.White else colorScheme.onSurface

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.widthIn(max = 420.dp),
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(
                onClick = onClose,
                containerColor = fabContainerColor,
                contentColor = fabContentColor,
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        colors =
            FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = toolbarContainerColor,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueSelectionToolbarAction(
                icon = if (allSelected) R.drawable.deselect else R.drawable.select_all,
                contentDescription = null,
                tint = toolbarContentColor,
                onClick = onToggleSelectAll,
            )

            QueueSelectionToolbarAction(
                icon = R.drawable.playlist_add,
                contentDescription = stringResource(R.string.add_to_playlist),
                tint = colorScheme.primary,
                onClick = onAddToPlaylist,
            )

            QueueSelectionToolbarAction(
                icon = R.drawable.queue_music,
                contentDescription = stringResource(R.string.create_playlist),
                tint = colorScheme.primary,
                onClick = onCreatePlaylist,
            )

            QueueSelectionToolbarAction(
                icon = R.drawable.delete,
                contentDescription = stringResource(R.string.delete),
                tint = colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun QueueSelectionToolbarAction(
    icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
    }
}
