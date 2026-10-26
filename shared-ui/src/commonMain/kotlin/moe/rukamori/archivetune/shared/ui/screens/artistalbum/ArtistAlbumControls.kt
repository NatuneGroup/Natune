/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassRole
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

/**
 * Persistent chrome over the hero. The pills are Liquid Glass when it is live; otherwise the same modifier chain
 * renders its flat twin, so the hit targets and positions do not move between the two states.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtistAlbumTopChrome(
    title: String,
    titleAlpha: Float,
    backDescription: String,
    moreDescription: String,
    contentColor: Color,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillFallback = Color.Black.copy(alpha = .35f)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .liquidGlassMaterial(shape = CircleShape, role = LiquidGlassRole.ActionMenu, fallbackColor = pillFallback)
                .combinedClickable(onClick = onBack, onLongClick = onBackLongClick)
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArtistAlbumIcon(symbol = ArtistAlbumSymbol.BACK, tint = contentColor, description = backDescription)
            Text(
                text = title,
                modifier = Modifier.alpha(titleAlpha).widthIn(max = 220.dp),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onMore,
            modifier = Modifier
                .clip(CircleShape)
                .liquidGlassMaterial(shape = CircleShape, role = LiquidGlassRole.ActionMenu, fallbackColor = pillFallback),
        ) {
            ArtistAlbumIcon(symbol = ArtistAlbumSymbol.MORE_HORIZONTAL, tint = contentColor, description = moreDescription)
        }
    }
}

/** Follow, shuffle, play and an optional radio action. Buttons are disabled rather than hidden when their data is absent. */
@Composable
internal fun ArtistAlbumPrimaryActions(
    followed: Boolean,
    followDescription: String,
    followedDescription: String,
    canPlay: Boolean,
    canShuffle: Boolean,
    playLabel: String,
    shuffleDescription: String,
    onFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    radioDescription: String? = null,
    onRadio: (() -> Unit)? = null,
) {
    val tint = LocalContentColor.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onFollow,
            modifier = Modifier.size(48.dp),
        ) {
            ArtistAlbumIcon(
                symbol = if (followed) ArtistAlbumSymbol.HEART_FILLED else ArtistAlbumSymbol.HEART,
                tint = tint,
                description = if (followed) followedDescription else followDescription,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onShuffle,
            enabled = canShuffle,
            modifier = Modifier.size(48.dp),
        ) {
            ArtistAlbumIcon(symbol = ArtistAlbumSymbol.SHUFFLE, tint = tint, description = shuffleDescription)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.height(48.dp),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            ArtistAlbumIcon(symbol = ArtistAlbumSymbol.PLAY, tint = LocalContentColor.current, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(playLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (onRadio != null && radioDescription != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onRadio,
                modifier = Modifier.size(48.dp),
            ) {
                ArtistAlbumIcon(symbol = ArtistAlbumSymbol.RADIO, tint = tint, description = radioDescription)
            }
        }
    }
}

/** A section heading. A heading with [onClick] shows a chevron and opens the complete section. */
@Composable
internal fun ArtistAlbumSectionTitle(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val base = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    Row(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            ArtistAlbumIcon(
                symbol = ArtistAlbumSymbol.CHEVRON_RIGHT,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        }
    }
}

/** One track row. The active row takes the primary colour and a playing overlay while playback is running. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtistAlbumTrackRow(
    item: ContentItem,
    subtitle: String,
    artwork: ContentArtwork,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    ordinal: Int? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) scheme.primary else scheme.onSurface,
            )
        },
        supportingContent = {
            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ordinal?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    artwork(item, Modifier.matchParentSize())
                    if (active && playing) {
                        Box(
                            modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ArtistAlbumIcon(symbol = ArtistAlbumSymbol.PLAYING, tint = Color.White, size = 20.dp)
                        }
                    }
                }
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) scheme.secondaryContainer else scheme.surface,
        ),
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/**
 * A square artwork card for albums, playlists, artists and songs. A null [width] fills the grid cell it is placed in;
 * a fixed width suits a horizontal row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtistAlbumCard(
    item: ContentItem,
    subtitle: String,
    artwork: ContentArtwork,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 152.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        artwork(item, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = if (active) scheme.primary else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The artist's latest release, shown above the sections. [metadata] is the release type and year line. */
@Composable
internal fun ArtistAlbumReleaseCard(
    release: ArtistReleaseUi,
    label: String,
    metadata: String,
    artwork: ContentArtwork,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            artwork(release.asContentItem(), Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = release.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Opens the complete list behind a truncated one. */
@Composable
internal fun ArtistAlbumViewAll(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Reveals on scroll up and hides on scroll down. Reduced motion swaps the animation for an instant change. */
@Composable
internal fun rememberRevealOnScrollUp(state: LazyListState): State<Boolean> {
    val revealed = remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var previousIndex = state.firstVisibleItemIndex
        var previousOffset = state.firstVisibleItemScrollOffset
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val movedDown = index > previousIndex || (index == previousIndex && offset > previousOffset)
            revealed.value = !movedDown
            previousIndex = index
            previousOffset = offset
        }
    }
    return revealed
}

@Composable
internal fun ArtistAlbumFab(
    visible: Boolean,
    label: String,
    symbol: ArtistAlbumSymbol,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalOpticalPolicy.current.motion
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
        exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { ArtistAlbumIcon(symbol = symbol, tint = LocalContentColor.current) },
            text = { Text(label) },
        )
    }
}

/**
 * A loading placeholder for a page still being fetched. With motion allowed it is an indeterminate indicator; with
 * reduced motion it is a static label, because an indeterminate indicator keeps looping. The baseline shimmer is not
 * ported, so no looping placeholder is drawn in either case.
 */
@Composable
internal fun ArtistAlbumLoading(modifier: Modifier = Modifier) {
    val motion = LocalOpticalPolicy.current.motion
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
        if (motion) {
            CircularProgressIndicator()
        } else {
            Text(
                text = stringResource(Res.string.m5_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A failure or empty state with at most one action. Retry only appears when the caller can actually retry. */
@Composable
internal fun ArtistAlbumStatus(
    title: String,
    description: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun ArtistAlbumMoreButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        ArtistAlbumIcon(
            symbol = ArtistAlbumSymbol.MORE_VERTICAL,
            tint = LocalContentColor.current,
            description = description,
        )
    }
}

/** One action in a menu sheet. A disabled action keeps its place in the list but takes no input. */
@Composable
internal fun ArtistAlbumSheetItem(
    text: String,
    symbol: ArtistAlbumSymbol,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { ArtistAlbumIcon(symbol = symbol, tint = LocalContentColor.current) },
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
