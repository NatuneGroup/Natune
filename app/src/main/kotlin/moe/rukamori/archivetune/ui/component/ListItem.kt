package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.constants.ListItemHeight

@Composable
inline fun ListItem(
    modifier: Modifier = Modifier,
    title: String,
    noinline subtitle: (@Composable RowScope.() -> Unit)? = null,
    thumbnailContent: @Composable () -> Unit,
    crossinline trailingContent: @Composable RowScope.() -> Unit = {},
    isActive: Boolean = false,
    showActiveContainer: Boolean = true,
    textColorOverride: Color? = null,
    noinline progressContent: (@Composable () -> Unit)? = null,
    // A minimum rather than a fixed height: text-bearing rows grow with the system font scale.
    rowHeight: Dp = ListItemHeight,
) {
    val titleColor =
        textColorOverride ?: if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val subtitleContentColor =
        textColorOverride?.copy(alpha = 0.7f) ?: if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val trailingContentColor =
        textColorOverride ?: if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .focusable()
                .heightIn(min = rowHeight)
                .padding(horizontal = 8.dp)
                .then(
                    if (isActive && showActiveContainer) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { thumbnailContent() }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor,
            )
            if (subtitle != null) {
                CompositionLocalProvider(LocalContentColor provides subtitleContentColor) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) { subtitle() }
                }
            }
            progressContent?.invoke()
        }
        CompositionLocalProvider(LocalContentColor provides trailingContentColor) {
            trailingContent()
        }
    }
}

@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    badges: @Composable RowScope.() -> Unit = {},
    thumbnailContent: @Composable () -> Unit,
    trailingContent: @Composable RowScope.() -> Unit = {},
    isActive: Boolean = false,
    showActiveContainer: Boolean = true,
    progressContent: (@Composable () -> Unit)? = null,
) = ListItem(
    title = title,
    modifier = modifier,
    isActive = isActive,
    showActiveContainer = showActiveContainer,
    progressContent = progressContent,
    subtitle = {
        badges()
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                color =
                    if (isActive) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(
                            alpha = 0.7f,
                        )
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    },
    thumbnailContent = thumbnailContent,
    trailingContent = trailingContent,
)
