package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Clean CMP implementation informed by Metrolist's public presentation concepts, not decompiled source. */
@Composable
internal fun MetrolistPlayer(c: PlayerRenderContext) {
    val vintage=c.preferences.metrolistPresentation==MetrolistPresentation.VINTAGE
    PlayerColumn(Modifier.testTag(if(vintage) "metrolist-vintage" else "metrolist-modern")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            ActionButton(PlayerAction.COLLAPSE,c)
            Text(stringResource(if(vintage) Res.string.m5_vintage else Res.string.m5_modern),
                style=MaterialTheme.typography.labelLarge)
            ActionButton(PlayerAction.LIKE,c)
        }
        if(c.landscape) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MetrolistArtwork(c,vintage)
                    PlayerMetadata(c,centered=vintage)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    PlayerSeek(c,native=PlayerSliderStyle.Simple)
                    NativeTransport(c,square=!vintage)
                }
            }
        } else {
            MetrolistArtwork(c,vintage)
            PlayerMetadata(c,centered=vintage)
            PlayerSeek(c,native=PlayerSliderStyle.Simple)
            NativeTransport(c,square=!vintage)
        }
        BottomControls(c,metrolist=true)
    }
}

@Composable
private fun MetrolistArtwork(c: PlayerRenderContext,vintage: Boolean) {
    val frame=if(vintage) Modifier.border(8.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(0.dp)).padding(16.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier.clip(RoundedCornerShape(12.dp))
    Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("metrolist-square-artwork").then(frame)) {
        Crossfade(c.state.playback?.occurrenceId,
            animationSpec=tween(if(c.motionActive) 300 else 0),label="metrolist-artwork") { occurrence ->
            c.slots.artwork(occurrence,Modifier.fillMaxSize())
        }
    }
}
