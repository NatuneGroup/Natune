@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_check_circle_linear
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_copy
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_more_circle_linear
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** One labelled value in a media-info card. */
internal data class MediaInfoDetailRow(
    val icon: DrawableResource,
    val label: String,
    val value: String,
)

/** A tappable pill in the header strip; tapping copies its text. */
internal data class MediaInfoQuickFact(
    val icon: DrawableResource,
    val text: String,
)

/** A headline number in the numbers tab. */
internal data class MediaInfoMetricRow(
    val icon: DrawableResource,
    val label: String,
    val value: String,
)

/** A provider row in the real-providers card. [worked] marks the provider that served the stream. */
internal data class MediaInfoProviderItem(
    val name: String,
    val note: String,
    val worked: Boolean,
)

@Composable
internal fun MediaInfoCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * A label/value row. A null [onClick] keeps the row display-only; the baseline section rows were
 * clickable with an empty action, which only produced an unused ripple.
 */
@Composable
internal fun MediaInfoRow(
    icon: DrawableResource,
    label: String,
    value: String,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(clickModifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showDivider) {
            MediaInfoDivider()
        }
    }
}

@Composable
internal fun MediaInfoDivider() {
    Box(
        modifier =
            Modifier
                .padding(start = 64.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .alpha(0.5f)
                .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Titled, display-only group of rows used by the playback, DAC, and output sections. */
@Composable
internal fun MediaInfoSectionCard(
    title: String,
    rows: List<MediaInfoDetailRow>,
) {
    if (rows.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MediaInfoSectionTitle(title)
            rows.forEachIndexed { index, item ->
                MediaInfoRow(
                    icon = item.icon,
                    label = item.label,
                    value = item.value,
                    showDivider = index != rows.lastIndex,
                    onClick = null,
                )
            }
        }
    }
}

@Composable
internal fun MediaInfoSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** Shown while provider data is still loading. */
@Composable
internal fun MediaInfoPendingCard(
    title: String,
    message: String,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
        ) {
            LoadingIndicator(modifier = Modifier.size(36.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MediaInfoNarrativeCard(
    icon: DrawableResource,
    title: String,
    body: String,
    copyContentDescription: String,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MediaInfoCopyButton(
                    contentDescription = copyContentDescription,
                    onClick = onCopy,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MediaInfoCopyButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.m9_media_info_solar_copy),
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Real-providers card. [workedLabel] and [standbyLabel] are shown as the status chip. */
@Composable
internal fun MediaInfoProvidersCard(
    title: String,
    providers: List<MediaInfoProviderItem>,
    workedLabel: String,
    standbyLabel: String,
) {
    if (providers.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MediaInfoSectionTitle(title)
            providers.forEachIndexed { index, provider ->
                MediaInfoProviderRow(
                    name = provider.name,
                    note = provider.note,
                    status = if (provider.worked) workedLabel else standbyLabel,
                    worked = provider.worked,
                    showDivider = index != providers.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun MediaInfoProviderRow(
    name: String,
    note: String,
    status: String,
    worked: Boolean,
    showDivider: Boolean,
) {
    val mainColor =
        if (worked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = mainColor,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter =
                            painterResource(
                                if (worked) {
                                    Res.drawable.m9_media_info_solar_check_circle_linear
                                } else {
                                    Res.drawable.m9_media_info_solar_more_circle_linear
                                },
                            ),
                        contentDescription = null,
                        tint =
                            if (worked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (worked) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = mainColor,
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        if (worked) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (showDivider) {
            MediaInfoDivider()
        }
    }
}

/** Headline metric tile used by the numbers tab. */
@Composable
internal fun MediaInfoMetricCard(
    icon: DrawableResource,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
