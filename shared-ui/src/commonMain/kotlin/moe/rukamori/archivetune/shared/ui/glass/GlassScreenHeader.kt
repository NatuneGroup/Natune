package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.home.HomeTopFadeBlur
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Header bar height the scrim fades below; the status-bar inset sits above it. */
val ScreenHeaderHazeBarZone = 64.dp

/**
 * The page-coloured top fade under a header that floats over scrolling content. It is a plain gradient, so it needs no
 * haze source. It draws only while the Liquid Glass look is on and ignores occlusion: the fade samples nothing, so a
 * player sheet must not make it appear and vanish.
 */
@Composable
fun ScreenHeaderHaze(
    systemBarsTopPadding: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!enabled || !LocalOpticalPolicy.current.liquidGlassLook) return
    HomeTopFadeBlur(
        pageColor = MaterialTheme.colorScheme.surface,
        barHeight = systemBarsTopPadding + ScreenHeaderHazeBarZone,
        modifier = modifier,
    )
}

/**
 * Glass state for a detail-screen header (album, artist or playlist). It owns the backdrop that the scrolling content
 * records into. While the policy suspends glass, [liquidGlassActive] is false and the header draws only the scrim.
 */
@Stable
class GlassScreenHeader(
    val liquidGlassActive: Boolean,
    val backdrop: PlatformBackdrop?,
)

/**
 * Creates the header glass state. The backdrop exists only while glass is recorded, so callers gate on
 * [GlassScreenHeader.liquidGlassActive] or [GlassScreenHeader.backdrop] alone.
 */
@Composable
fun rememberGlassScreenHeader(): GlassScreenHeader {
    val active = LocalOpticalPolicy.current.recordLiquidGlass
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backdrop = if (active) rememberBackdrop(surfaceColor) else null
    return remember(active, backdrop) { GlassScreenHeader(liquidGlassActive = active, backdrop = backdrop) }
}

/**
 * Tags the scrolling content as the header's backdrop. Without a live backdrop it does nothing, so it is safe to apply
 * unconditionally. It has no haze source: the scrim is a gradient, and a source that nothing samples would still record
 * the list into a layer every frame.
 */
fun Modifier.glassHeaderSource(header: GlassScreenHeader): Modifier {
    val backdrop = header.backdrop ?: return this
    return layerBackdrop(backdrop)
}

/**
 * The header drawn over the scrolling content as a sibling of the recorded source, never inside it. The scrim comes
 * first, then the back pill with the title, then an optional search or trailing pill at the end. Long-press on back
 * returns to the main tab root, as the flat management headers do.
 */
@Composable
fun BoxScope.GlassScreenHeaderOverlay(
    header: GlassScreenHeader,
    title: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val systemBarsTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    ScreenHeaderHaze(systemBarsTopPadding = systemBarsTopPadding)

    val backdrop = header.backdrop
    if (!header.liquidGlassActive || backdrop == null) return

    LiquidGlassActionPill(
        backdrop = backdrop,
        interactive = true,
        modifier = modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(role = Role.Button, onClick = onBack, onLongClick = onBackLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4_icon_arrow_back),
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        // The frosted-pill title look of the baseline large top bar: bold, single line, tinted for glass.
        MarqueeText(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 12.dp),
        )
    }

    if (onSearch != null || trailing != null) {
        LiquidGlassActionPill(
            backdrop = backdrop,
            modifier = modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
        ) {
            if (onSearch != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_search),
                        contentDescription = stringResource(Res.string.m4_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                trailing?.invoke(this)
            }
        }
    }
}
