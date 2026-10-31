package moe.rukamori.archivetune.ui.screens.settings.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import moe.rukamori.archivetune.ui.screens.settings.segmentedSettingsItemShape

/**
 * One row of a stacked About group on a plain tonal surface. The corner radii come from the same
 * [segmentedSettingsItemShape] as the settings pages, so a group keeps its shape. There is no glass
 * pass, backdrop or runtime shader behind it; the Material ripple shows presses.
 */
@Composable
internal fun AboutTonalSegment(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(index, count) { segmentedSettingsItemShape(index, count) }
    val surface =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    if (onClick == null) {
        Box(modifier = surface, content = content)
    } else {
        Box(modifier = surface.clickable(onClick = onClick), content = content)
    }
}
