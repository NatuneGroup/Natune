package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*

/**
 * SpatialFlow look: inset artwork pager, centred metadata, the wavy scrubber and a single
 * rounded pill carrying previous / play / next / like. The optional SpatialFlow font marks
 * the tag so hosts can verify the fallback. Queue/lyrics live in the sliding SpatialFlowPanel.
 */
@Composable
internal fun SpatialFlowPlayer(c: PlayerRenderContext) {
    PlayerColumn(Modifier.testTag(if (c.state.spatialFont == null) "spatial-font-fallback" else "spatial-font-installed")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE, c); ActionButton(PlayerAction.MORE, c)
        }
        if (c.landscape) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerArtworkPager(c, Modifier.weight(1f))
            Column(Modifier.weight(1f)) { SpatialFlowControls(c) }
        } else {
            PlayerArtworkPager(c, Modifier.fillMaxWidth(.86f).align(Alignment.CenterHorizontally))
            SpatialFlowControls(c)
        }
        c.slots.compactLyrics(Modifier.fillMaxWidth().height(88.dp))
        BottomControls(c)
    }
}

@Composable
private fun SpatialFlowControls(c: PlayerRenderContext) {
    Column {
        PlayerMetadata(c, centered = true)
        PlayerSeek(c, native = PlayerSliderStyle.Wavy)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton(PlayerAction.PREVIOUS, c); PlayButton(c, large = false)
            ActionButton(PlayerAction.NEXT, c); ActionButton(PlayerAction.LIKE, c)
        }
    }
}
