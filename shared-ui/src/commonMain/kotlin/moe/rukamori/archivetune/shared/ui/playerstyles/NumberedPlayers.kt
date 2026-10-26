package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.ui.player.*

@Composable
internal fun PlayerSleeve(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    radius: Float = 16f,
    canvasAllowed: Boolean = true,
) {
    val ordinary=c.preferences.style.capabilities.ordinarySleeve
    val hidden=ordinary && c.preferences.hideSleeve
    val r=if(ordinary) c.preferences.sleeveRadius else radius
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(r.dp)).graphicsLayer { alpha=if(hidden) 0f else 1f }
        .testTag("player-sleeve")) {
        if(canvasAllowed && c.state.canvasAvailable && c.preferences.style.capabilities.artworkCanvas) {
            c.slots.canvas(c.state.playback?.occurrenceId,c.surfaces.artwork && !hidden,Modifier.fillMaxSize())
        } else c.slots.artwork(c.state.playback?.occurrenceId,Modifier.fillMaxSize())
    }
}

@Composable
internal fun NumberedPlayer(c: PlayerRenderContext) {
    val style=c.preferences.style
    if(c.landscape) {
        Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlayerSleeve(c,Modifier.weight(if(style==PlayerDesignStyle.V5) .6f else 1f))
            PlayerColumn(Modifier.weight(1f)) { NumberedContent(c,artwork=false) }
        }
    } else PlayerColumn { NumberedContent(c,artwork=true) }
}

@Composable
private fun ColumnScope.NumberedContent(c: PlayerRenderContext,artwork: Boolean) {
    val style=c.preferences.style
    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(style==PlayerDesignStyle.V1) Arrangement.SpaceBetween else Arrangement.End) {
        ActionButton(PlayerAction.COLLAPSE,c)
        ActionButton(PlayerAction.SHARE,c)
        if(style!=PlayerDesignStyle.V1) ActionButton(PlayerAction.LIKE,c)
        if(style in listOf(PlayerDesignStyle.V1,PlayerDesignStyle.V4,PlayerDesignStyle.V6)) ActionButton(PlayerAction.MORE,c)
    }
    if(artwork) Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        PlayerSleeve(c,Modifier.fillMaxWidth(if(style==PlayerDesignStyle.V5) .54f else if(style==PlayerDesignStyle.V3) .78f else .95f),
            radius=if(style==PlayerDesignStyle.V6) 40f else 12f)
    }
    PlayerMetadata(c,centered=style==PlayerDesignStyle.V2 || style==PlayerDesignStyle.V3)
    if(style==PlayerDesignStyle.V5) c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
    PlayerSeek(c)
    NumberedTransport(c)
    BottomControls(c)
}

@Composable
internal fun NumberedTransport(c: PlayerRenderContext) {
    val style=c.preferences.style
    val secondary=c.preferences.secondaryButtons && style.capabilities.sharedControls
    when(style) {
        PlayerDesignStyle.V1,PlayerDesignStyle.V5 -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceEvenly) {
            ActionButton(PlayerAction.REPEAT,c)
            ActionButton(PlayerAction.PREVIOUS,c,secondary=secondary)
            PlayButton(c)
            ActionButton(PlayerAction.NEXT,c,secondary=secondary)
            ActionButton(PlayerAction.LIKE,c)
        }
        PlayerDesignStyle.V2 -> Column {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                ActionButton(PlayerAction.PREVIOUS,c,secondary=secondary); PlayButton(c,square=true)
                ActionButton(PlayerAction.NEXT,c,secondary=secondary)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.REPEAT,c)
            }
        }
        PlayerDesignStyle.V4 -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,
            verticalAlignment=Alignment.CenterVertically) {
            Column { ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.PREVIOUS,c,secondary=secondary) }
            PlayButton(c,large=true,square=true)
            Column { ActionButton(PlayerAction.NEXT,c,secondary=secondary); ActionButton(PlayerAction.REPEAT,c) }
        }
        PlayerDesignStyle.V6 -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                ActionButton(PlayerAction.PREVIOUS,c,secondary=secondary); PlayButton(c,square=true)
                ActionButton(PlayerAction.NEXT,c,secondary=secondary)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.REPEAT,c)
            }
        }
        else -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            ActionButton(PlayerAction.SHUFFLE,c); ActionButton(PlayerAction.PREVIOUS,c,secondary=secondary)
            PlayButton(c,large=style!=PlayerDesignStyle.V3)
            ActionButton(PlayerAction.NEXT,c,secondary=secondary); ActionButton(PlayerAction.REPEAT,c)
        }
    }
}

@Composable
internal fun ImmersivePlayer(c: PlayerRenderContext,extended: Boolean) {
    Box(Modifier.fillMaxSize().testTag(if(extended) "immersive-extended" else "immersive")) {
        if(extended || c.preferences.background==PlayerBackgroundStyle.DEFAULT) {
            if(c.state.canvasAvailable) c.slots.canvas(c.state.playback?.occurrenceId,
                c.surfaces.artwork,Modifier.fillMaxSize())
            else c.slots.artwork(c.state.playback?.occurrenceId,Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(androidx.compose.ui.graphics.Color.Transparent,androidx.compose.ui.graphics.Color.Black.copy(alpha=.9f)))))
        PlayerColumn {
            ActionButton(PlayerAction.COLLAPSE,c)
            Spacer(Modifier.height(if(c.landscape) 40.dp else if(extended) 180.dp else 250.dp))
            PlayerMetadata(c)
            if(extended) c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
            PlayerSeek(c,remaining=true)
            NumberedTransport(c)
            PlayerVolume(c)
            BottomControls(c)
        }
    }
}

@Composable
internal fun MaterialExtendedPlayer(c: PlayerRenderContext) {
    val sheetColor = c.state.palette.firstOrNull()?.let {
        androidx.compose.ui.graphics.lerp(it, MaterialTheme.colorScheme.surface, .82f)
    } ?: MaterialTheme.colorScheme.surfaceContainerHigh
    PlayerColumn(Modifier.clip(RoundedCornerShape(32.dp)).background(sheetColor).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE,c); ActionButton(PlayerAction.MORE,c)
        }
        if(c.landscape) Row(verticalAlignment=Alignment.CenterVertically) {
            PlayerSleeve(c,Modifier.weight(1f),28f)
            Column(Modifier.weight(1f)) { PlayerMetadata(c); PlayerSeek(c); NumberedTransport(c) }
        } else {
            PlayerSleeve(c,Modifier.fillMaxWidth(),28f); PlayerMetadata(c,centered=true)
            PlayerSeek(c); NumberedTransport(c)
        }
        BottomControls(c)
    }
}

@Composable
internal fun EditorialPlayer(c: PlayerRenderContext) {
    PlayerColumn {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE,c); ActionButton(PlayerAction.SHARE,c)
        }
        PlayerMetadata(c,editorial=true)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PlayerSleeve(c,Modifier.weight(1f),0f)
            Column { ActionButton(PlayerAction.LIKE,c); ActionButton(PlayerAction.MORE,c) }
        }
        PlayerSeek(c); NumberedTransport(c); BottomControls(c)
    }
}

@Composable
internal fun AppleMusicPlayer(c: PlayerRenderContext) {
    PlayerColumn {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE,c); ActionButton(PlayerAction.MORE,c)
        }
        if(c.landscape) Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            PlayerSleeve(c,Modifier.weight(1f))
            Column(Modifier.weight(1f)) { PlayerMetadata(c); PlayerSeek(c); NativeTransport(c) }
        } else {
            PlayerSleeve(c,Modifier.fillMaxWidth(.94f)); PlayerMetadata(c)
            PlayerSeek(c); NativeTransport(c)
        }
        PlayerVolume(c)
        c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
        BottomControls(c)
    }
}

@Composable
internal fun NativeTransport(c: PlayerRenderContext,square: Boolean = false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
        ActionButton(PlayerAction.PREVIOUS,c); PlayButton(c,square=square); ActionButton(PlayerAction.NEXT,c)
    }
}
