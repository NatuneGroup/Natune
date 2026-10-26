package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.home.HomeFeedGutter
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** True when the player is on this track or on its album, which is what marks a card as active. */
internal fun HomeVariantNowPlaying.isActive(ref: ContentRef): Boolean = ref == trackRef || ref == albumRef

/** Podcast and episode cards carry no sheet, as the baseline cards do. */
internal fun ContentKind.hasHomeMenu(): Boolean = this != ContentKind.PODCAST && this != ContentKind.EPISODE

/**
 * The category row of the Rukamori page. The selected chip shows the done glyph and is cleared by tapping it again,
 * which the owner handles through [onSelect].
 */
@Composable
internal fun HomeVariantChipRow(
    chips: List<HomeVariantChip>,
    selectedKey: String?,
    onSelect: (HomeVariantChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = HomeFeedGutter)
                .padding(vertical = 10.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip.key == selectedKey
            val leadingIcon: (@Composable () -> Unit)? =
                if (selected) {
                    {
                        Icon(
                            painter = painterResource(Res.drawable.m4hv_icon_done),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                }
            FilterChip(
                selected = selected,
                onClick = { onSelect(chip) },
                label = {
                    Text(
                        text = chip.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = leadingIcon,
            )
        }
    }
}

/**
 * Shared loading, empty and failure pane. [onRetry] is null when there is nothing to retry. [actionLabel] and
 * [onAction] add one optional follow-up button, such as the Spotify not-connected route to YouTube Music; both must
 * be set for the button to show.
 */
@Composable
internal fun HomeVariantStatePane(
    contentPadding: PaddingValues,
    showLoading: Boolean,
    icon: DrawableResource?,
    message: String?,
    onRetry: (() -> Unit)?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (showLoading) {
                LoadingIndicator()
            } else {
                icon?.let {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
                message?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(Res.string.m4hv_retry))
                    }
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

/** The 20dp glyph that leads a section header, quiet and before the title. */
@Composable
internal fun HomeVariantLeadingIcon(icon: DrawableResource) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** The spinner appended while the owner loads the next page. */
@Composable
internal fun HomeVariantLoadingMore(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().padding(32.dp),
    ) {
        LoadingIndicator()
    }
}
